package pe.axiz.spring.audit;

import java.util.Objects;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import pe.axiz.adapter.outbox.r2dbc.ReactiveOutboxWriter;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditPublishException;
import reactor.core.publisher.Mono;

/**
 * Durable audit in the same Reactor transaction as business data. Failures go to the native
 * fallback because their business transaction will roll back. No CompletionStage bridge is used.
 */
public final class ReactiveTransactionalAuditPublisher implements ReactiveAuditPublisher {
  private final java.util.function.Function<AuditEvent, Mono<Void>> append;
  private final ReactiveAuditPublisher fallback;
  private final AuditOutsideTransaction outside;

  public ReactiveTransactionalAuditPublisher(
      ReactiveOutboxWriter outbox,
      String destination,
      ReactiveAuditPublisher fallback,
      AuditOutsideTransaction outside) {
    Objects.requireNonNull(outbox);
    this.append =
        event -> outbox.write(destination, TransactionalAuditPublisher.envelope(event), "audit");
    Objects.requireNonNull(destination);
    this.fallback = Objects.requireNonNull(fallback);
    this.outside = Objects.requireNonNull(outside);
  }

  @Override
  public Mono<Void> publish(AuditEvent event) {
    return Mono.defer(
        () -> {
          if (event.outcome() == AuditEvent.Outcome.FAILURE) return fallback.publish(event);
          return TransactionSynchronizationManager.forCurrentTransaction()
              .map(TransactionSynchronizationManager::isActualTransactionActive)
              .onErrorResume(NoTransactionException.class, ignored -> Mono.just(false))
              .flatMap(
                  active -> {
                    if (active) return append.apply(event);
                    if (outside == AuditOutsideTransaction.FAIL)
                      return Mono.error(
                          new AuditPublishException(
                              "Audit event "
                                  + event.id()
                                  + " recorded outside a reactive transaction",
                              new IllegalStateException("no active transaction")));
                    return fallback.publish(event);
                  });
        });
  }
}
