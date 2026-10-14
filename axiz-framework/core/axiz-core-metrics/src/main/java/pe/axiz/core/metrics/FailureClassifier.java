package pe.axiz.core.metrics;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/**
 * Derives outcome and reason of a failed operation. Classifiers are chained; the errors starter
 * contributes one that turns business errors into {@code rejected} with the catalog code as reason.
 */
@FunctionalInterface
public interface FailureClassifier {

  Optional<Classification> classify(Throwable failure);

  /**
   * Classification of a failure.
   *
   * @param outcome outcome tag
   * @param reason reason tag (bounded: error codes or exception types, never messages)
   */
  record Classification(Outcome outcome, String reason) {}

  /** Default classifier: timeouts, cancellations and otherwise errors by exception type. */
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
