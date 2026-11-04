package pe.axiz.payments.networkgateway.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.identifiers.IdentifierRegistry;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricsSettings;
import pe.axiz.core.money.Money;
import pe.axiz.payments.networkgateway.application.port.out.PaymentNetwork;
import pe.axiz.payments.networkgateway.domain.GatewayErrors;
import pe.axiz.payments.networkgateway.domain.Transfer;
import pe.axiz.payments.networkgateway.domain.TransferOrder;
import pe.axiz.payments.networkgateway.domain.TransferStatus;

class SendTransferUseCaseTest {
  final EventMetrics metrics =
      new EventMetrics(new SimpleMeterRegistry(), MetricsSettings.defaults());

  @Test
  void transfersToInvalidAccountsNeverReachTheNetwork() {
    List<String> sent = new ArrayList<>();
    PaymentNetwork network =
        (o, _) -> {
          sent.add(o.creditorCci());
          return new Transfer("NET-1", TransferStatus.SETTLED, o.reference());
        };
    SendTransferUseCase send =
        new SendTransferUseCase(network, IdentifierRegistry.withDiscoveredTypes(), metrics);

    assertThatThrownBy(
            () ->
                send.execute(
                    new SendTransferUseCase.Input(
                        new TransferOrder("00219100123456789099", Money.of("10", "PEN"), "OP-2"),
                        "k")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.definition()).isEqualTo(GatewayErrors.TRANSFER_REJECTED));
    assertThat(sent).isEmpty();

    Transfer ok =
        send.execute(
            new SendTransferUseCase.Input(
                new TransferOrder("00219100123456789053", Money.of("10", "PEN"), "OP-3"), "k"));
    assertThat(ok.status()).isEqualTo(TransferStatus.SETTLED);
    assertThat(sent).containsExactly("00219100123456789053");
  }
}
