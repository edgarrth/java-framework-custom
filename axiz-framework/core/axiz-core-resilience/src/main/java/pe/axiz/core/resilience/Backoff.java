package pe.axiz.core.resilience;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Backoff exponencial con jitter completo: {@code random(0, min(maxDelay, initial *
 * multiplier^n))}. El jitter evita reintentos sincronizados desde muchas réplicas.
 *
 * @param initialDelay retraso antes del primer reintento
 * @param multiplier factor de crecimiento (al menos 1)
 * @param maxDelay tope
 * @param jitter indica si se aplica jitter completo
 */
public record Backoff(Duration initialDelay, double multiplier, Duration maxDelay, boolean jitter) {

  public Backoff {
    Objects.requireNonNull(initialDelay);
    Objects.requireNonNull(maxDelay);
    if (initialDelay.isNegative() || maxDelay.compareTo(initialDelay) < 0 || multiplier < 1.0) {
      throw new IllegalArgumentException("Invalid backoff");
    }
  }

  public static Backoff exponential(Duration initial, Duration max) {
    return new Backoff(initial, 2.0, max, true);
  }

  public static Backoff fixed(Duration delay) {
    return new Backoff(delay, 1.0, delay, false);
  }

  /** Retraso antes del reintento número {@code retry} (1 = primer reintento). */
  @SuppressFBWarnings(
      value = "PREDICTABLE_RANDOM",
      justification =
          "ThreadLocalRandom provides non-security retry jitter only; no credential, token or "
              + "identifier is generated.")
  public Duration delay(int retry) {
    double base = (double) initialDelay.toMillis() * Math.pow(multiplier, Math.max(0, retry - 1));
    long capped = (long) Math.min(base, (double) maxDelay.toMillis());
    if (!jitter || capped == 0) {
      return Duration.ofMillis(capped);
    }
    return Duration.ofMillis(ThreadLocalRandom.current().nextLong(capped + 1));
  }
}
