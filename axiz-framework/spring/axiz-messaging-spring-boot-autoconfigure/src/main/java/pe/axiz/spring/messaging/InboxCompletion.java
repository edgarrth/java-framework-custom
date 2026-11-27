package pe.axiz.spring.messaging;

/** Integración JDBC aportada solo cuando se conoce el recurso de conexión del inbox. */
public interface InboxCompletion {
  Object transactionResource();

  boolean complete(String consumer, String messageKey, String owner);
}
