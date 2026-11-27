package pe.axiz.core.messaging;

/** No se pudo decodificar el payload: reintentar no ayudará. */
public class PayloadException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public PayloadException(String message, Throwable cause) {
    super(message, cause);
  }
}
