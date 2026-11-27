package pe.axiz.payments.history.adapter.out.persistence;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import pe.axiz.core.api.Page;
import pe.axiz.core.api.PageRequest;
import pe.axiz.payments.history.application.port.out.PaymentHistories;
import pe.axiz.payments.history.domain.PaymentHistory;
import pe.axiz.payments.history.domain.PaymentStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@link PaymentHistories} sobre Aurora mediante R2DBC: cada sentencia se une a la transacción
 * reactiva del pipeline de comandos o consultas y se ejecuta sin bloquear un hilo.
 */
@Component
class PaymentHistoryR2dbcAdapter implements PaymentHistories {

  private final PaymentHistoryRepository rows;

  PaymentHistoryR2dbcAdapter(PaymentHistoryRepository rows) {
    this.rows = rows;
  }

  @Override
  public Mono<PaymentHistory> apply(String paymentId, Change change) {
    return rows.findByPaymentId(paymentId)
        .map(row -> row.copy(change.apply(row.toDomain())))
        .switchIfEmpty(Mono.fromSupplier(() -> PaymentHistoryRow.of(change.apply(null))))
        .flatMap(rows::save)
        .map(PaymentHistoryRow::toDomain);
  }

  @Override
  public Mono<PaymentHistory> find(String paymentId) {
    return rows.findByPaymentId(paymentId).map(PaymentHistoryRow::toDomain);
  }

  @Override
  public Mono<Page<PaymentHistory>> byCustomer(
      String customerId, @Nullable PaymentStatus status, PageRequest page) {
    org.springframework.data.domain.PageRequest pageable =
        org.springframework.data.domain.PageRequest.of(page.page(), page.size());
    Flux<PaymentHistoryRow> content =
        status == null
            ? rows.findByCustomerIdOrderByLastChangeAtDesc(customerId, pageable)
            : rows.findByCustomerIdAndStatusOrderByLastChangeAtDesc(
                customerId, status.name(), pageable);
    Mono<Long> total =
        status == null
            ? rows.countByCustomerId(customerId)
            : rows.countByCustomerIdAndStatus(customerId, status.name());
    return Mono.zip(content.map(PaymentHistoryRow::toDomain).collectList(), total)
        .map(t -> Page.of(t.getT1(), page, t.getT2()));
  }
}
