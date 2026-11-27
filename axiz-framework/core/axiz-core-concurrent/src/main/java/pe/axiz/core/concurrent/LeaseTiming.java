package pe.axiz.core.concurrent;

import java.time.Duration;
import java.util.Objects;

/**
 * Plazos de lease conservadores medidos con un reloj monotónico, independientes del desfase del
 * reloj de pared.
 */
public final class LeaseTiming {
  private LeaseTiming() {}

  public static Duration margin(Duration lease) {
    Objects.requireNonNull(lease);
    if (!lease.isPositive()) throw new IllegalArgumentException("lease must be positive");
    return Duration.ofNanos(lease.toNanos() / 100).plusMillis(2);
  }

  /**
   * El plazo resultante se compara mediante resta, incluso a través del desbordamiento de nanoTime.
   */
  public static long validUntil(long sent, Duration lease) {
    return sent + lease.toNanos() - margin(lease).toNanos();
  }
}
