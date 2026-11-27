package pe.axiz.payments.process.application.port.out;

/** Estado de la orden de pago en payment-orders. Cada llamada es idempotente por clave. */
public interface PaymentOrders {

  void markProcessing(String orderId, String idempotencyKey);

  void complete(String orderId, String idempotencyKey);

  void reject(String orderId, String reason, String idempotencyKey);
}
