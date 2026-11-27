package pe.axiz.core.metrics;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/**
 * Deriva el resultado y el motivo de una operación fallida. Los clasificadores se encadenan; el
 * starter de errores aporta uno que convierte los errores de negocio en {@code rejected} con el
 * código del catálogo como motivo.
 */
@FunctionalInterface
public interface FailureClassifier {

  Optional<Classification> classify(Throwable failure);

  /**
   * Clasificación de un fallo.
   *
   * @param outcome tag de resultado
   * @param reason tag de motivo (acotado: códigos de error o tipos de excepción, nunca mensajes)
   */
  record Classification(Outcome outcome, String reason) {}

  /**
   * Clasificador por defecto: timeouts, cancelaciones y, en otro caso, errores por tipo de
   * excepción.
   */
  static FailureClassifier defaults() {
    return failure -> {
      for (Throwable t = failure; t != null; t = t.getCause()) {
        if (t instanceof TimeoutException
            || t.getClass().getSimpleName().endsWith("TimeoutException")) {
          return Optional.of(new Classification(Outcome.TIMEOUT, "timeout"));
        }
        if (t instanceof CancellationException || t instanceof InterruptedException) {
          return Optional.of(new Classification(Outcome.CANCELLED, "cancelled"));
        }
        if (t.getCause() == t) {
          break;
        }
      }
      String type = failure.getClass().getSimpleName();
      String reason = type.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
      return Optional.of(new Classification(Outcome.ERROR, reason.isEmpty() ? "error" : reason));
    };
  }
}
