package pe.axiz.quality.archunit;

import java.util.Locale;

/** architecture contract service structure. */
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
