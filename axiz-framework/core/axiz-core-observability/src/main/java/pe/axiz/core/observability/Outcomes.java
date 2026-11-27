package pe.axiz.core.observability;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Clasifica el resultado de una operación en los valores de resultado estándar. */
public final class Outcomes {

  public static final String SUCCESS = "success";
  public static final String REJECTED = "rejected";
  public static final String ERROR = "error";
  public static final String TIMEOUT = "timeout";
  public static final String CANCELLED = "cancelled";

  private Outcomes() {}

  /**
   * Resultado para un fallo. Los rechazos de negocio se reconocen mediante el clasificador indicado
   * (la capacidad de errores reconoce errores de negocio, de validación y de seguridad).
   *
   * @param error el fallo; null significa éxito
   * @param isRejection indica si el fallo es un rechazo de negocio esperado
   * @return valor de resultado
   */
  public static String of(@Nullable Throwable error, Function<Throwable, Boolean> isRejection) {
    if (error == null) {
      return SUCCESS;
    }
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof TimeoutException || t.getClass().getSimpleName().contains("Timeout")) {
        return TIMEOUT;
      }
      if (t instanceof CancellationException || t instanceof InterruptedException) {
        return CANCELLED;
      }
      if (Boolean.TRUE.equals(isRejection.apply(t))) {
        return REJECTED;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return ERROR;
  }
}
