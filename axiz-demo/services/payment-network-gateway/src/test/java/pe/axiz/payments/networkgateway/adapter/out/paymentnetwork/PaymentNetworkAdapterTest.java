package pe.axiz.payments.networkgateway.adapter.out.paymentnetwork;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.ExternalErrorDetail;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.money.Money;
import pe.axiz.payments.networkgateway.adapter.out.paymentnetwork.model.NetworkReceipt;
import pe.axiz.payments.networkgateway.domain.TransferOrder;
import pe.axiz.payments.networkgateway.domain.TransferStatus;

class PaymentNetworkAdapterTest {

  final TransferOrder order =
      new TransferOrder("00219100123456789053", Money.of("150.75", "PEN"), "OP-1");

  @Test
  void mapsReceiptsAndUnknownResultsArePending() {
    PaymentNetworkAdapter ok =
        new PaymentNetworkAdapter((_, _) -> new NetworkReceipt("NET-55", "SETTLED", "OP-1"));
    PaymentNetworkAdapter odd =
        new PaymentNetworkAdapter((_, _) -> new NetworkReceipt("NET-56", "QUEUED", "OP-1"));

    assertThat(ok.send(order, "k").status()).isEqualTo(TransferStatus.SETTLED);
    assertThat(odd.send(order, "k").status()).isEqualTo(TransferStatus.PENDING);
  }

  @Test
  void rejectionsKeepAStableReasonAndOtherFailuresPassThrough() {
    PaymentNetworkAdapter rejecting =
        new PaymentNetworkAdapter(
            (_, _) -> {
              throw ExternalServiceException.from(
                  new ExternalErrorDetail("payment-network", 422, "NET-R02", "closed", "{}"));
            });
    assertThatThrownBy(() -> rejecting.send(order, "k"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.parameters()).containsEntry("reason", "account-closed"));

    ExternalServiceException down =
        ExternalServiceException.from(
            new ExternalErrorDetail("payment-network", 503, null, null, ""));
    PaymentNetworkAdapter failing =
        new PaymentNetworkAdapter(
            (_, _) -> {
              throw down;
            });
    assertThatThrownBy(() -> failing.send(order, "k")).isSameAs(down);
  }
}
