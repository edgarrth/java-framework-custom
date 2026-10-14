package pe.axiz.core.messaging;

/** The payload could not be decoded: retrying will not help. */
public class PayloadException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public PayloadException(String message, Throwable cause) {
    super(message, cause);
  }
}
