package pe.axiz.core.time;

import java.time.ZoneId;
import java.util.Objects;

/**
 * Configuración de tiempo de un servicio. Las fechas de negocio se evalúan en {@link #zone()}; los
 * instantes siempre se almacenan en UTC.
 *
 * @param zone zona horaria de negocio, {@code America/Lima} por defecto
 */
public record TimeSettings(ZoneId zone) {

  public static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Lima");

  public TimeSettings {
    Objects.requireNonNull(zone, "zone");
  }

  public static TimeSettings defaults() {
    return new TimeSettings(DEFAULT_ZONE);
  }
}
