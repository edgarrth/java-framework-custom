package pe.axiz.core.messaging;

/**
 * Maneja un tipo de evento. Los handlers deben ser idempotentes; el inbox elimina duplicados, pero
 * los handlers igualmente deben tolerar replays.
 *
 * @param <T> tipo del payload
 */
public interface EventHandler<T> {

  /** Tipo CloudEvents que se maneja, por ejemplo {@code pe.axiz.payments.order-placed.v1}. */
  String eventType();

  Class<T> payloadType();

  void handle(T payload, EventEnvelope envelope) throws Exception;
}
