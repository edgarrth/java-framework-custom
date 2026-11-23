package pe.axiz.core.messaging;

/** El broker no confirmó el evento: el fallo siempre es visible para quien llama. */
public class PublishException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public PublishException(String message, Throwable cause) {
    super(message, cause);
  }
}
