package pe.axiz.core.outbox;

/** Outbox persistence failure. */
public class OutboxException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OutboxException(String message) {
    super(message);
  }

  public OutboxException(String message, Throwable cause) {
    super(message, cause);
  }
}
