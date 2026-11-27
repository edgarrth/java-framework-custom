package pe.axiz.core.soap;

/** Fallo técnico de SOAP (XML mal formado, serialización). */
public class SoapException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SoapException(String message, Throwable cause) {
    super(message, cause);
  }

  public SoapException(String message) {
    super(message);
  }
}
