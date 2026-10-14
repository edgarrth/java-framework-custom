package pe.axiz.core.time;

import java.time.ZoneId;
import java.util.Objects;

/**
 * Time settings of a service. Business dates are evaluated in {@link #zone()}; instants are always
 * stored in UTC.
 *
 * @param zone business time zone, {@code America/Lima} by default
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
