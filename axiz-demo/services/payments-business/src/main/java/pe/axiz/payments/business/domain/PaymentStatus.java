package pe.axiz.payments.business.domain;

import java.util.Map;
import java.util.Set;

/** Lifecycle of a payment order. */
public enum PaymentStatus {
  RECEIVED,
  PROCESSING,
  COMPLETED,
  REJECTED;

  private static final Map<PaymentStatus, Set<PaymentStatus>> NEXT =
      Map.of(
          RECEIVED, Set.of(PROCESSING, REJECTED),
          PROCESSING, Set.of(COMPLETED, REJECTED),
          COMPLETED, Set.of(),
          REJECTED, Set.of());

  /** Whether the order can move from this status to {@code next}. */
  public boolean canMoveTo(PaymentStatus next) {
    return NEXT.get(this).contains(next);
  }

  public boolean isFinal() {
    return NEXT.get(this).isEmpty();
  }
}
