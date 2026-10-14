package pe.axiz.core.resilience;

import java.time.Duration;
import java.util.Objects;

/**
 * Timeouts that every outbound client must define (there is no "infinite" default).
 *
 * @param connect connection establishment
 * @param read waiting for response data
 * @param total whole call including retries
 */
public record TimeoutSettings(Duration connect, Duration read, Duration total) {

  public TimeoutSettings {
    Objects.requireNonNull(connect, "connect timeout is required");
    Objects.requireNonNull(read, "read timeout is required");
    Objects.requireNonNull(total, "total timeout is required");
    if (connect.isZero()
        || connect.isNegative()
        || read.isZero()
        || read.isNegative()
        || total.isZero()
        || total.isNegative()) {
      throw new IllegalArgumentException("Timeouts must be positive");
    }
    if (total.compareTo(read) < 0) {
      throw new IllegalArgumentException("Total timeout must be at least the read timeout");
    }
  }

  public static TimeoutSettings defaults() {
    return new TimeoutSettings(
        Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(15));
  }
}
