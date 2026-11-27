package pe.axiz.quality.archunit;

import java.util.Locale;

/** Extensión de servicio. */
public enum Extension {
  CQRS;

  public static Extension parse(String value) {
    return valueOf(value.strip().replace('-', '_').toUpperCase(Locale.ROOT));
  }
}
