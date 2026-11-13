package pe.axiz.payments.bankingcore.adapter.out.corebanking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.ExternalErrorDetail;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.core.errors.TechnicalException;
import pe.axiz.core.money.Money;
import pe.axiz.core.sensitive.ObjectMasker;
import pe.axiz.payments.bankingcore.adapter.out.corebanking.model.CoreDebit;
import pe.axiz.payments.bankingcore.adapter.out.corebanking.model.CoreMovement;
import pe.axiz.payments.bankingcore.adapter.out.soap.CoreCreditGateway;
import pe.axiz.payments.bankingcore.domain.IntegrationErrors;
import pe.axiz.payments.bankingcore.domain.Movement;
import pe.axiz.payments.bankingcore.domain.MovementOrder;
import pe.axiz.payments.bankingcore.domain.MovementStatus;
import pe.axiz.spring.soap.AxizSoapProperties;
import pe.axiz.spring.soap.SoapClients;

class CoreBankingAdapterTest {

  static ExternalServiceException core(int status, String code) {
    return ExternalServiceException.from(
        new ExternalErrorDetail("core-banking", status, code, "core says no", "{}"));
  }

  /** Core REST facade fake. */
  static final class FakeClient implements CoreBankingClient {
    RuntimeException failure;
    String lastAccount;
    String lastRef;
    CoreDebit lastBody;

    @Override
    public CoreMovement postDebit(String account, String xRequestRef, CoreDebit body) {
      if (failure != null) {
        throw failure;
      }
      lastAccount = account;
      lastRef = xRequestRef;
      lastBody = body;
      return new CoreMovement("MV-1", "P");
    }

    @Override
    public CoreMovement postReversal(String mvId, String xRequestRef) {
      if (failure != null) {
        throw failure;
      }
      return new CoreMovement("MV-2", "R");
    }
  }

  final FakeClient client = new FakeClient();
  final CoreBankingAdapter adapter = new CoreBankingAdapter(client, null);
  final MovementOrder order =
      new MovementOrder("1910012345678", Money.of("150.75", "PEN"), "OP20261011-00000001");

  @Test
  void debitsTranslateTheCoreModel() {
    Movement m = adapter.debit(order, "key-1");

    assertThat(m).isEqualTo(new Movement("MV-1", MovementStatus.POSTED, "OP20261011-00000001"));
    assertThat(client.lastRef).isEqualTo("key-1");
    assertThat(client.lastBody)
        .isEqualTo(new CoreDebit("150.75", "PEN", "OP20261011-00000001", null));
  }

  @Test
  void coreRejectionsBecomeOurErrors() {
    client.failure = core(422, "CB-51");
    assertThatThrownBy(() -> adapter.debit(order, "k"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.definition()).isEqualTo(IntegrationErrors.INSUFFICIENT_FUNDS));

    client.failure = core(422, "CB-63");
    assertThatThrownBy(() -> adapter.debit(order, "k"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.definition()).isEqualTo(IntegrationErrors.ACCOUNT_NOT_OPERATIVE));

    client.failure = core(503, null);
    assertThatThrownBy(() -> adapter.debit(order, "k")).isSameAs(client.failure);
  }

  @Test
  void reversingAnUnknownMovementIsNotFound() {
    client.failure = core(404, "CB-14");
    assertThatThrownBy(() -> adapter.reverse("MV-404", "k")).isInstanceOf(NotFoundException.class);

    client.failure = null;
    assertThat(adapter.reverse("MV-1", "k").status()).isEqualTo(MovementStatus.REVERSED);
  }

  @Test
  void otherReversalFailuresStayDependencyFailures() {
    client.failure = core(503, null);
    assertThatThrownBy(() -> adapter.reverse("MV-1", "k")).isSameAs(client.failure);

    client.failure = core(422, "CB-62");
    assertThatThrownBy(() -> adapter.reverse("MV-1", "k"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.definition()).isEqualTo(IntegrationErrors.ACCOUNT_NOT_OPERATIVE));
  }

  @Test
  void creditsGoThroughTheSoapServiceAndItsFaultsAreTranslated() {
    List<ExternalServiceException> faults = new ArrayList<>();
    CoreCreditGateway credits =
        new CoreCreditGateway(
            new SoapClients(
                new AxizSoapProperties(Map.of()),
                null,
                ObjectMasker.defaults(),
                ObservationRegistry.NOOP,
                Clock.systemUTC(),
                TransportSecurityPolicy.system())) {
          @Override
          public Movement credit(MovementOrder o) {
            if (!faults.isEmpty()) {
              throw faults.removeFirst();
            }
            return new Movement("MV-C", MovementStatus.POSTED, o.reference());
          }
        };
    CoreBankingAdapter withCredits = new CoreBankingAdapter(client, credits);

    assertThat(withCredits.credit(order, "k").id()).isEqualTo("MV-C");

    faults.add(core(422, "CB-17"));
    assertThatThrownBy(() -> withCredits.credit(order, "k"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.definition()).isEqualTo(IntegrationErrors.ACCOUNT_NOT_OWNED));

    ExternalServiceException down = core(502, null);
    faults.add(down);
    assertThatThrownBy(() -> withCredits.credit(order, "k")).isSameAs(down);
  }

  @Test
  void unknownCoreStatesAreTechnicalErrors() {
    assertThatThrownBy(() -> CoreBankingAdapter.state("X")).isInstanceOf(TechnicalException.class);
    assertThatThrownBy(() -> CoreBankingAdapter.state(null)).isInstanceOf(TechnicalException.class);
  }
}
