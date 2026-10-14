package pe.axiz.core.soap;

/** Technical SOAP failure (malformed XML, serialization). */
public class SoapException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SoapException(String message, Throwable cause) {
    super(message, cause);
  }

  public SoapException(String message) {
    super(message);
  }
}
