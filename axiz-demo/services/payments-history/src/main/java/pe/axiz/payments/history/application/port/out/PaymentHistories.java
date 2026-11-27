package pe.axiz.payments.history.application.port.out;

import org.jspecify.annotations.Nullable;
import pe.axiz.core.api.Page;
import pe.axiz.core.api.PageRequest;
import pe.axiz.payments.history.domain.PaymentHistory;
import pe.axiz.payments.history.domain.PaymentStatus;
import reactor.core.publisher.Mono;

/** Almacén no bloqueante del historial de pagos (Aurora sobre R2DBC). */
public interface PaymentHistories {

  /** Cambio de una entrada, a partir de su estado actual. */
  @FunctionalInterface
  interface Change {
    PaymentHistory apply(@Nullable PaymentHistory current);
  }

  /**
   * Lee la entrada de un pago, aplica el cambio y almacena el resultado (bloqueo optimista: una
   * actualización concurrente falla y el evento se reentrega).
   */
  Mono<PaymentHistory> apply(String paymentId, Change change);

  Mono<PaymentHistory> find(String paymentId);

  /**
   * Pagos de un cliente, con el cambio más reciente primero y, de forma opcional, de un solo
   * estado.
   */
  Mono<Page<PaymentHistory>> byCustomer(
      String customerId, @Nullable PaymentStatus status, PageRequest page);
}
