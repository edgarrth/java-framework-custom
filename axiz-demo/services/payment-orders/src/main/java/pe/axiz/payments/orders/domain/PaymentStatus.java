package pe.axiz.payments.orders.domain;

import java.util.Set;

/** Ciclo de vida de una orden de pago. */
public enum PaymentStatus {
  RECEIVED,
  PROCESSING,
  COMPLETED,
  REJECTED;

  /** Estados a los que la orden puede pasar desde este (ninguno para un estado final). */
  public Set<PaymentStatus> next() {
    // un switch exhaustivo: un estado nuevo no compila hasta que se declaren sus transiciones
    return switch (this) {
      case RECEIVED -> Set.of(PROCESSING, REJECTED);
      case PROCESSING -> Set.of(COMPLETED, REJECTED);
      case COMPLETED, REJECTED -> Set.of();
    };
  }

  /** Indica si la orden puede pasar de este estado a {@code next}. */
  public boolean canMoveTo(PaymentStatus next) {
    return next().contains(next);
  }

  public boolean isFinal() {
    return next().isEmpty();
  }
}
