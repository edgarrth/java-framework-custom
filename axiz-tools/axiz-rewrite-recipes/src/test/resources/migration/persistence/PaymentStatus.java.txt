package pe.axiz.payments.history.domain;

/** Status of a payment order, as published by payments-business. */
public enum PaymentStatus {
  RECEIVED,
  PROCESSING,
  COMPLETED,
  REJECTED;

  public boolean isFinal() {
    return this == COMPLETED || this == REJECTED;
  }
}
