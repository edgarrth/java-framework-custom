package pe.axiz.payments.process.application.port.out;

/** Status of the payment order in payments-business. Every call is idempotent per key. */
public interface PaymentOrders {

  void markProcessing(String orderId, String idempotencyKey);

  void complete(String orderId, String idempotencyKey);

  void reject(String orderId, String reason, String idempotencyKey);
}
