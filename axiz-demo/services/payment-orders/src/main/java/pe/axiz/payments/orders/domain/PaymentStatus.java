package pe.axiz.payments.orders.domain;

import java.util.Set;

/** Lifecycle of a payment order. */
public enum PaymentStatus {
  RECEIVED,
  PROCESSING,
  COMPLETED,
  REJECTED;

  /** Statuses the order can move to from this one (none for a final status). */
  public Set<PaymentStatus> next() {
    // an exhaustive switch: a new status does not compile until its transitions are declared
    return switch (this) {
      case RECEIVED -> Set.of(PROCESSING, REJECTED);
      case PROCESSING -> Set.of(COMPLETED, REJECTED);
      case COMPLETED, REJECTED -> Set.of();
    };
  }

  /** Whether the order can move from this status to {@code next}. */
  public boolean canMoveTo(PaymentStatus next) {
    return next().contains(next);
  }

  public boolean isFinal() {
    return next().isEmpty();
  }
}
