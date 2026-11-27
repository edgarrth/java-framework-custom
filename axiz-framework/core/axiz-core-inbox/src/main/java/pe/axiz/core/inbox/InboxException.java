package pe.axiz.core.inbox;

/** Fallo de persistencia del inbox. */
public class InboxException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InboxException(String message, Throwable cause) {
    super(message, cause);
  }
}
