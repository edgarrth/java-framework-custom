package pe.axiz.payments.history.adapter.out.persistence;

import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Repositorio Spring Data R2DBC de {@link PaymentHistoryRow} (consultas derivadas de los nombres).
 */
interface PaymentHistoryRepository extends ReactiveCrudRepository<PaymentHistoryRow, UUID> {

  Mono<PaymentHistoryRow> findByPaymentId(String paymentId);

  Flux<PaymentHistoryRow> findByCustomerIdOrderByLastChangeAtDesc(String customerId, Pageable page);

  Mono<Long> countByCustomerId(String customerId);

  Flux<PaymentHistoryRow> findByCustomerIdAndStatusOrderByLastChangeAtDesc(
      String customerId, String status, Pageable page);

  Mono<Long> countByCustomerIdAndStatus(String customerId, String status);
}
