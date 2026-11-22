package pe.axiz.spring.messaging;

/** JDBC integration supplied only when the inbox connection resource is known. */
public interface InboxCompletion {
  Object transactionResource();

  boolean complete(String consumer, String messageKey, String owner);
}
