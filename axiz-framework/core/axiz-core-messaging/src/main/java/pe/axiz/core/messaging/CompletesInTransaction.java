package pe.axiz.core.messaging;

/** Handler boundary that commits the business effect and the claimed inbox completion together. */
public interface CompletesInTransaction<T> extends EventHandler<T> {
  boolean completesInTransaction();

  void handleClaimed(
      T payload, EventEnvelope envelope, String consumer, String messageKey, String owner)
      throws Exception;
}
