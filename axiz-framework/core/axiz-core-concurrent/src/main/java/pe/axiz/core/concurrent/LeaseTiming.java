package pe.axiz.core.concurrent;

import java.time.Duration;
import java.util.Objects;

/** Conservative lease deadlines measured with a monotonic clock, independent of wall-clock skew. */
public final class LeaseTiming {
  private LeaseTiming() {}

  public static Duration margin(Duration lease) {
    Objects.requireNonNull(lease);
    if (!lease.isPositive()) throw new IllegalArgumentException("lease must be positive");
    return Duration.ofNanos(lease.toNanos() / 100).plusMillis(2);
  }

  /** Use subtraction to compare the resulting deadline, including across nanoTime wraparound. */
  public static long validUntil(long sent, Duration lease) {
    return sent + lease.toNanos() - margin(lease).toNanos();
  }
}
