package pe.axiz.quality.archunit;

import java.util.Locale;

/** Stack del servicio. */
public enum Stack {
  SERVLET,
  REACTIVE;

  public static Stack parse(String value) {
    return valueOf(value.strip().replace('-', '_').toUpperCase(Locale.ROOT));
  }
}
