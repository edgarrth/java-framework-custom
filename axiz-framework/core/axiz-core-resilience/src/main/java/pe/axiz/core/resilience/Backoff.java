package pe.axiz.core.resilience;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with full jitter: {@code random(0, min(maxDelay, initial * multiplier^n))}.
 * Jitter avoids synchronized retries from many replicas.
 *
 * @param initialDelay delay before the first retry
 * @param multiplier growth factor (at least 1)
 * @param maxDelay cap
 * @param jitter whether to apply full jitter
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

  /** Delay before retry number {@code retry} (1 = first retry). */
  public Duration delay(int retry) {
    double base = initialDelay.toMillis() * Math.pow(multiplier, Math.max(0, retry - 1));
    long capped = (long) Math.min(base, maxDelay.toMillis());
    if (!jitter || capped == 0) {
      return Duration.ofMillis(capped);
    }
    return Duration.ofMillis(ThreadLocalRandom.current().nextLong(capped + 1));
  }
}
