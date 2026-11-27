package pe.axiz.payments.process.adapter.out.paymentorders;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.ExternalErrorDetail;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.payments.process.adapter.out.paymentorders.model.PaymentOrder;
import pe.axiz.payments.process.adapter.out.paymentorders.model.PaymentOrderPage;
import pe.axiz.payments.process.adapter.out.paymentorders.model.PaymentOrderRequest;
import pe.axiz.payments.process.adapter.out.paymentorders.model.PaymentStatus;
import pe.axiz.payments.process.adapter.out.paymentorders.model.StatusChange;

class PaymentOrdersAdapterTest {

  /** Fake de negocio que rechaza cada cambio con 409 e informa un estado fijo. */
  static final class Conflicting implements PaymentOrdersClient {
    final PaymentStatus current;

    Conflicting(PaymentStatus current) {
      this.current = current;
    }

    @Override
    public PaymentOrder createPaymentOrder(String key, PaymentOrderRequest body) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PaymentOrderPage listPaymentOrders(
        String customerId, @Nullable Integer page, @Nullable Integer size) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PaymentOrder getPaymentOrder(String orderId) {
      return new PaymentOrder(
          orderId, "OP", "C-1", current, BigDecimal.TEN, "PEN", "****9053", null, null);
    }

    @Override
    public PaymentOrder changePaymentOrderStatus(String orderId, String key, StatusChange body) {
      throw ExternalServiceException.from(
          new ExternalErrorDetail(
              "payment-orders", 409, "PAYMENTS-PAYMENTORDERS-0003", "conflict", "{}"));
    }
  }

  @Test
  void aConflictIsDoneWhenTheOrderIsAlreadyThere() {
    PaymentOrdersAdapter adapter =
        new PaymentOrdersAdapter(new Conflicting(PaymentStatus.PROCESSING));
    assertThatCode(() -> adapter.markProcessing("O-1", "k")).doesNotThrowAnyException();
  }

  @Test
  void aRealConflictIsReported() {
    PaymentOrdersAdapter adapter =
        new PaymentOrdersAdapter(new Conflicting(PaymentStatus.REJECTED));
    assertThatThrownBy(() -> adapter.complete("O-1", "k"))
        .isInstanceOf(ExternalServiceException.class);
  }
}
