package pe.axiz.core.messaging;

/**
 * Frontera del handler que hace commit del efecto de negocio y de la finalización reclamada del
 * inbox a la vez.
 */
public interface CompletesInTransaction<T> extends EventHandler<T> {
  boolean completesInTransaction();

  void handleClaimed(
      T payload, EventEnvelope envelope, String consumer, String messageKey, String owner)
      throws Exception;
}
