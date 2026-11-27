package pe.axiz.core.errors;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

/** Una dependencia falló o rechazó la llamada. */
public class ExternalServiceException extends AxizException {

  private static final long serialVersionUID = 1L;

  public ExternalServiceException(
      ErrorDefinition definition, ExternalErrorDetail detail, @Nullable Throwable cause) {
    super(definition, message(detail), Map.of("system", detail.system()), List.of(), detail, cause);
  }

  /**
   * Construye la excepción a partir de la respuesta de la dependencia y elige la categoría según su
   * estado.
   *
   * @param detail lo que respondió la dependencia
   * @return la excepción
   */
  public static ExternalServiceException from(ExternalErrorDetail detail) {
    return from(detail, null);
  }

  /**
   * Construye la excepción a partir de la respuesta de la dependencia o del fallo de transporte,
   * conservando la causa. Un fallo de transporte es un timeout (504) cuando la causa lo es; en otro
   * caso la dependencia no está disponible (503: conexión rechazada o reiniciada, circuito abierto,
   * bulkhead lleno).
   *
   * @param detail lo que respondió la dependencia (estado -1 cuando no respondió)
   * @param cause el fallo de transporte, si existe
   * @return la excepción
   */
  public static ExternalServiceException from(
      ExternalErrorDetail detail, @Nullable Throwable cause) {
    ErrorCategory category;
    if (!detail.transportError()) {
      category = ErrorCategory.forExternalStatus(detail.status());
    } else if (isTimeout(cause)) {
      category = ErrorCategory.GATEWAY_TIMEOUT;
    } else {
      category = ErrorCategory.SERVICE_UNAVAILABLE;
    }
    ErrorDefinition def =
        new ErrorDefinition(
            CoreErrors.EXTERNAL_ERROR.code(),
            category,
            ErrorType.EXTERNAL,
            CoreErrors.EXTERNAL_ERROR.title(),
            CoreErrors.EXTERNAL_ERROR.messageKey());
    if (category == ErrorCategory.GATEWAY_TIMEOUT) {
      def = CoreErrors.EXTERNAL_TIMEOUT;
    }
    return new ExternalServiceException(def, detail, cause);
  }

  /**
   * {@code true} cuando el fallo, o una de sus causas, es un timeout: los del JDK y los de
   * librerías de cliente HTTP de las que este módulo no depende (por ejemplo, {@code
   * ReadTimeoutException} de Netty), reconocidos por el sufijo {@code TimeoutException} de su tipo.
   */
  static boolean isTimeout(@Nullable Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof SocketTimeoutException
          || t instanceof HttpTimeoutException
          || t instanceof TimeoutException
          || t.getClass().getSimpleName().endsWith("TimeoutException")) {
        return true;
      }
    }
    return false;
  }

  // un fallo de transporte no tiene estado que mostrar: "failed with status -1" no aportaría
  // información
  private static String message(ExternalErrorDetail detail) {
    if (!detail.transportError()) {
      return "Call to " + detail.system() + " failed with status " + detail.status();
    }
    String reason = detail.code() != null ? detail.code() : detail.message();
    return "Call to "
        + detail.system()
        + " got no response"
        + (reason != null && !reason.isBlank() ? " (" + reason + ")" : "");
  }

  public ExternalErrorDetail detail() {
    ExternalErrorDetail d = external();
    if (d == null) {
      throw new IllegalStateException("external detail missing");
    }
    return d;
  }
}
