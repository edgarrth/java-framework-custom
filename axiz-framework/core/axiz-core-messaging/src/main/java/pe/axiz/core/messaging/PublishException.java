package pe.axiz.core.messaging;

/** The broker did not confirm the event: the failure is always visible to the caller. */
public class PublishException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public PublishException(String message, Throwable cause) {
    super(message, cause);
  }
}
