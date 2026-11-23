package pe.axiz.payments.process.adapter.out.paymentorders;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.payments.process.adapter.out.paymentorders.model.PaymentOrder;
import pe.axiz.payments.process.adapter.out.paymentorders.model.PaymentStatus;
import pe.axiz.payments.process.adapter.out.paymentorders.model.StatusChange;
import pe.axiz.payments.process.application.port.out.PaymentOrders;

/**
 * Mueve órdenes de pago en payment-orders (client credentials, grupo de servicios HTTP {@code
 * payment-orders}). Un 409 porque la orden ya está en el estado de destino cuenta como hecho, de
 * modo que una saga reanudada puede repetir un paso con seguridad.
 */
@Component
public class PaymentOrdersAdapter implements PaymentOrders {

  private final PaymentOrdersClient client;

  public PaymentOrdersAdapter(PaymentOrdersClient client) {
    this.client = client;
  }

  @Override
  public void markProcessing(String orderId, String idempotencyKey) {
    move(orderId, PaymentStatus.PROCESSING, null, idempotencyKey);
  }

  @Override
  public void complete(String orderId, String idempotencyKey) {
    move(orderId, PaymentStatus.COMPLETED, null, idempotencyKey);
  }

  @Override
  public void reject(String orderId, String reason, String idempotencyKey) {
    move(orderId, PaymentStatus.REJECTED, reason, idempotencyKey);
  }

  private void move(
      String orderId, PaymentStatus target, @Nullable String reason, String idempotencyKey) {
    try {
      client.changePaymentOrderStatus(orderId, idempotencyKey, new StatusChange(target, reason));
    } catch (ExternalServiceException e) {
      if (e.detail().status() == 409 && alreadyIn(orderId, target)) {
        return;
      }
      throw e;
    }
  }

  private boolean alreadyIn(String orderId, PaymentStatus target) {
    PaymentOrder current = client.getPaymentOrder(orderId);
    return current.status() == target;
  }
}
