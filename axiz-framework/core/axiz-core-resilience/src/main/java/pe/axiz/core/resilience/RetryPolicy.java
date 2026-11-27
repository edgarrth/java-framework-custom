package pe.axiz.core.resilience;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Forma de reintentar una operación.
 *
 * @param maxAttempts intentos totales incluido el primero (1 significa sin reintento)
 * @param backoff retraso entre intentos
 * @param retryOn qué fallos se reintentan
 */
public record RetryPolicy(int maxAttempts, Backoff backoff, Predicate<Throwable> retryOn) {

  public RetryPolicy {
    if (maxAttempts < 1 || maxAttempts > 10) {
      throw new IllegalArgumentException("maxAttempts must be between 1 and 10");
    }
    Objects.requireNonNull(backoff);
    Objects.requireNonNull(retryOn);
  }

  public static RetryPolicy none() {
    return new RetryPolicy(1, Backoff.fixed(Duration.ZERO), t -> false);
  }

  public static RetryPolicy transientFailures(int maxAttempts, Backoff backoff) {
    return new RetryPolicy(maxAttempts, backoff, RetryDecider::isTransient);
  }
}
