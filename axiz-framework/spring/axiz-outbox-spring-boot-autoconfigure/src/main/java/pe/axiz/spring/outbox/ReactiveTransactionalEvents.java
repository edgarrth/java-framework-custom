package pe.axiz.spring.outbox;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.outbox.r2dbc.ReactiveOutboxWriter;
import reactor.core.publisher.Mono;

/**
 * Reactive counterpart of {@link TransactionalEvents} for R2DBC services: the event is inserted in
 * the outbox with the same connection and transaction as the business change ({@code
 * TransactionalOperator} or {@code @Transactional} on a method returning {@code Mono}/{@code
 * Flux}); the relay publishes it after commit. Subscribing outside a reactive transaction fails.
 */
public class ReactiveTransactionalEvents {

  static final String NO_TRANSACTION = "Outbox events must be enqueued inside a transaction";

  private final EventFactory factory;
  private final ReactiveOutboxWriter writer;

  public ReactiveTransactionalEvents(EventFactory factory, ReactiveOutboxWriter writer) {
    this.factory = Objects.requireNonNull(factory);
    this.writer = Objects.requireNonNull(writer);
  }

  /**
   * Enqueues an event; nothing happens until the returned {@code Mono} is subscribed within the
   * transaction.
   *
   * @param destination logical destination
   * @param eventId domain event id (becomes the CloudEvents id and the FIFO deduplication id)
   * @param type event type without prefix
   * @param aggregateType aggregate type, e.g. {@code payment}
   * @param aggregateId aggregate id (CloudEvents subject and partition key)
   * @param payload payload
   * @return the stored envelope
   */
  public Mono<EventEnvelope> enqueue(
      String destination,
      @Nullable String eventId,
      String type,
      String aggregateType,
      @Nullable String aggregateId,
      Object payload) {
    return TransactionSynchronizationManager.forCurrentTransaction()
        .onErrorMap(NoTransactionException.class, e -> new IllegalStateException(NO_TRANSACTION, e))
        .filter(TransactionSynchronizationManager::isActualTransactionActive)
        .switchIfEmpty(Mono.error(() -> new IllegalStateException(NO_TRANSACTION)))
        .flatMap(
            tx ->
                Mono.deferContextual(
                    ctx -> {
                      RequestMetadata fromContext = ctx.getOrDefault(RequestContext.KEY, null);
                      EventEnvelope envelope =
                          factory.create(
                              eventId,
                              type,
                              aggregateId,
                              payload,
                              fromContext != null ? fromContext : RequestContext.get());
                      return writer
                          .write(destination, envelope, aggregateType)
                          .thenReturn(envelope);
                    }));
  }
}
