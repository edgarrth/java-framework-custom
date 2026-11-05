package pe.axiz.spring.foundation;

/** JDBC integration supplied only when the inbox connection resource is known. */
public interface InboxCompletion {
  Object transactionResource();

  boolean complete(String consumer, String messageKey, String owner);
}
