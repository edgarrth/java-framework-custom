package pe.axiz.payments.history.application.port.out;

import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.Page;
import pe.axiz.core.commons.PageRequest;
import pe.axiz.payments.history.domain.PaymentHistory;
import pe.axiz.payments.history.domain.PaymentStatus;
import reactor.core.publisher.Mono;

/** Non-blocking store of the payment history (Aurora over R2DBC). */
public interface PaymentHistories {

  /** A change of one entry, given its current state. */
  @FunctionalInterface
  interface Change {
    PaymentHistory apply(@Nullable PaymentHistory current);
  }

  /**
   * Reads the entry of a payment, applies the change and stores the result (optimistic locking: a
   * concurrent update fails and the event is redelivered).
   */
  Mono<PaymentHistory> apply(String paymentId, Change change);

  Mono<PaymentHistory> find(String paymentId);

  /** Payments of a customer, latest change first, optionally of one status. */
  Mono<Page<PaymentHistory>> byCustomer(
      String customerId, @Nullable PaymentStatus status, PageRequest page);
}
