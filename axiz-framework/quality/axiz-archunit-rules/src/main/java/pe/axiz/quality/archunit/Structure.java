package pe.axiz.quality.archunit;

import java.util.Locale;

/** Estructura del servicio. */
public enum Structure {
  LAYERED,
  HEXAGONAL,
  EDA,
  BFF,
  BATCH;

  public static Structure parse(String value) {
    return valueOf(value.strip().replace('-', '_').toUpperCase(Locale.ROOT));
  }
}
