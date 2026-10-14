package pe.axiz.core.inbox;

/** Inbox persistence failure. */
public class InboxException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InboxException(String message, Throwable cause) {
    super(message, cause);
  }
}
