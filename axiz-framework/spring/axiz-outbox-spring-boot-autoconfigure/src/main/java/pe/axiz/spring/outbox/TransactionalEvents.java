package pe.axiz.spring.outbox;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.outbox.OutboxWriter;

/**
 * Records events in the outbox inside the caller's transaction; the relay publishes them after
 * commit. Calling it outside a transaction is an error, because the event could be stored without
 * the business change (or the opposite). The writer keeps the trace of the caller with the event
 * ({@link pe.axiz.core.outbox.OutboxWriteTracing}).
 */
public class TransactionalEvents {

  private final EventFactory factory;
  private final OutboxWriter writer;

  public TransactionalEvents(EventFactory factory, OutboxWriter writer) {
    this.factory = Objects.requireNonNull(factory);
    this.writer = Objects.requireNonNull(writer);
  }

  /**
   * Enqueues an event.
   *
   * @param destination logical destination
   * @param eventId domain event id (becomes the CloudEvents id and the FIFO deduplication id)
   * @param type event type without prefix
   * @param aggregateType aggregate type, e.g. {@code payment}
   * @param aggregateId aggregate id (CloudEvents subject and partition key)
   * @param payload payload
   * @return the stored envelope
   */
  public EventEnvelope enqueue(
      String destination,
      @Nullable String eventId,
      String type,
      String aggregateType,
      @Nullable String aggregateId,
      Object payload) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Outbox events must be enqueued inside a transaction");
    }
    EventEnvelope envelope =
        factory.create(eventId, type, aggregateId, payload, RequestContext.get());
    writer.write(destination, envelope, aggregateType);
    return envelope;
  }
}
