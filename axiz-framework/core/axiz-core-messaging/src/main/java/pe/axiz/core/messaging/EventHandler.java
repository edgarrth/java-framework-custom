package pe.axiz.core.messaging;

/**
 * Handles one event type. Handlers must be idempotent; the inbox removes duplicates but handlers
 * should still tolerate replays.
 *
 * @param <T> payload type
 */
public interface EventHandler<T> {

  /** CloudEvents type handled, e.g. {@code pe.axiz.payments.order-placed.v1}. */
  String eventType();

  Class<T> payloadType();

  void handle(T payload, EventEnvelope envelope) throws Exception;
}
