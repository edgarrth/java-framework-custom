package pe.axiz.core.resilience;

import java.time.Duration;
import java.util.Objects;

/**
 * Timeouts que todo cliente saliente debe definir (no hay valor por defecto "infinito").
 *
 * @param connect establecimiento de la conexión
 * @param read espera de datos de la respuesta
 * @param total llamada completa, incluidos los reintentos
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
