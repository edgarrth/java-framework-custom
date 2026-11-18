package pe.axiz.payments.ux.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.errors.ErrorDefinition;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.core.featureflags.FeatureFlags;
import pe.axiz.core.featureflags.FlagDefinition;
import pe.axiz.core.featureflags.MapFlagProvider;
import pe.axiz.payments.ux.composition.model.PaymentRequest;
import pe.axiz.payments.ux.composition.model.PaymentView;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Mono;

@ExtendWith(BlockHoundExtension.class)
class PaymentsCompositionTest {

  final Fakes.Crud crud =
      new Fakes.Crud()
          .with("C-1", "B-01", "Landlord", Fakes.CCI, "PEN")
          .with("C-1", "B-02", "Dollar savings", "00319300987654321003", "USD");
  final Fakes.Business business = new Fakes.Business();

  PaymentsComposition composition(FlagDefinition usd) {
    return new PaymentsComposition(
        business,
        new BeneficiaryDirectory(crud),
        new FeatureFlags(new MapFlagProvider(List.of(usd))),
        Duration.ofSeconds(1));
  }

  final PaymentsComposition composition = composition(FlagDefinition.off("payments-usd"));

  static PaymentRequest request(String beneficiaryId, String currency) {
    return new PaymentRequest()
        .beneficiaryId(beneficiaryId)
        .debtorAccount("1910012345678")
        .amount(new BigDecimal("150.75"))
        .currency(currency)
        .description("Rent");
  }

  static <T> T as(String customer, Mono<T> mono) {
    return mono.contextWrite(Fakes.signedIn(customer)).block(Duration.ofSeconds(5));
  }

  static void assertCode(Throwable e, ErrorDefinition expected) {
    assertThat(e).isInstanceOf(AxizException.class);
    assertThat(((AxizException) e).definition()).isEqualTo(expected);
  }

  @Test
  void paysABeneficiaryWithTheCallersKeyAndMasksTheAccount() {
    PaymentView view = as("C-1", composition.createPayment("key-1", request("B-01", "PEN")));

    assertThat(business.received.get("key-1").customerId()).isEqualTo("C-1");
    assertThat(business.received.get("key-1").creditorCci()).isEqualTo(Fakes.CCI);
    assertThat(view.getBeneficiaryAlias()).isEqualTo("Landlord");
    assertThat(view.getCreditorCci()).isEqualTo("****************9053");
    assertThat(view.getStatus()).isEqualTo("RECEIVED");
  }

  @Test
  void anAmountWithMoreDecimalsThanTheCurrencyIsRefusedAndNeverSentOn() {
    for (String amount : List.of("150.755", "1e99999999")) {
      PaymentRequest request = request("MISSING", "PEN").amount(new BigDecimal(amount));
      assertThatThrownBy(() -> as("C-1", composition.createPayment("k-" + amount, request)))
          .satisfies(e -> assertCode(e, pe.axiz.core.money.MoneyErrors.INVALID_AMOUNT));
    }
    assertThat(business.received).isEmpty();
    assertThat(crud.getCalls).isZero();
  }

  @Test
  void theCustomerComesFromTheTokenNeverFromTheRequest() {
    assertThatThrownBy(() -> composition.createPayment("k", request("B-01", "PEN")).block())
        .satisfies(e -> assertCode(e, CoreErrors.UNAUTHENTICATED));
    assertThatThrownBy(() -> as("C-2", composition.createPayment("k", request("B-01", "PEN"))))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void rejectsACurrencyDifferentFromTheBeneficiary() {
    assertThatThrownBy(() -> as("C-1", composition.createPayment("k", request("B-01", "XXX"))))
        .satisfies(e -> assertCode(e, ChannelErrors.CURRENCY_NOT_AVAILABLE));
    PaymentsComposition usdOn = composition(FlagDefinition.on("payments-usd"));
    assertThatThrownBy(() -> as("C-1", usdOn.createPayment("k", request("B-01", "USD"))))
        .satisfies(e -> assertCode(e, ChannelErrors.CURRENCY_MISMATCH));
    assertThat(business.received).isEmpty();
  }

  @Test
  void dollarsNeedTheFeatureFlag() {
    assertThatThrownBy(() -> as("C-1", composition.createPayment("k", request("B-02", "USD"))))
        .satisfies(e -> assertCode(e, ChannelErrors.CURRENCY_NOT_AVAILABLE));

    PaymentsComposition usdOn = composition(FlagDefinition.on("payments-usd"));
    assertThat(as("C-1", usdOn.createPayment("k", request("B-02", "USD"))).getCurrency())
        .isEqualTo("USD");
  }

  @Test
  void translatesDownstreamBusinessErrorsToChannelErrors() {
    business.failure = Fakes.downstream(422, "PAYMENTS-PAYMENTORDERS-0001");
    assertThatThrownBy(() -> as("C-1", composition.createPayment("k", request("B-01", "PEN"))))
        .isInstanceOf(BusinessException.class)
        .satisfies(e -> assertCode(e, ChannelErrors.LIMIT_EXCEEDED));

    business.failure = Fakes.downstream(503, null);
    assertThatThrownBy(() -> as("C-1", composition.createPayment("k", request("B-01", "PEN"))))
        .isInstanceOf(ExternalServiceException.class);
  }

  @Test
  void readsOwnPaymentsWithTheAliasAndHidesOthers() {
    as("C-1", composition.createPayment("key-1", request("B-01", "PEN")));

    PaymentView view = as("C-1", composition.getPayment("P-1"));
    assertThat(view.getBeneficiaryAlias()).isEqualTo("Landlord");

    assertThatThrownBy(() -> as("C-9", composition.getPayment("P-1")))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> as("C-1", composition.getPayment("P-404")))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void theAliasIsOptionalWhenBeneficiariesAreDown() {
    as("C-1", composition.createPayment("key-1", request("B-01", "PEN")));
    crud.listFailure = new IllegalStateException("beneficiaries down");

    PaymentView view = as("C-1", composition.getPayment("P-1"));

    assertThat(view.getPaymentId()).isEqualTo("P-1");
    assertThat(view.getBeneficiaryAlias()).isNull();
  }
}
