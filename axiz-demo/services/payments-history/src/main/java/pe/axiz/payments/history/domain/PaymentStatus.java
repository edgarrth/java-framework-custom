package pe.axiz.payments.history.domain;

/** Estado de una orden de pago, tal como lo publica payment-orders. */
public enum PaymentStatus {
  RECEIVED,
  PROCESSING,
  COMPLETED,
  REJECTED;

  public boolean isFinal() {
    return this == COMPLETED || this == REJECTED;
  }
}
