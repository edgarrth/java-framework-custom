package pe.axiz.quality.archunit;

import java.util.Locale;

/** architecture contract service extension. */
public enum Extension {
  CQRS;

  public static Extension parse(String value) {
    return valueOf(value.strip().replace('-', '_').toUpperCase(Locale.ROOT));
  }
}
