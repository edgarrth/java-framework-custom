package pe.axiz.quality.archunit;

import java.util.Locale;

/** architecture contract service stack. */
public enum Stack {
  SERVLET,
  REACTIVE;

  public static Stack parse(String value) {
    return valueOf(value.strip().replace('-', '_').toUpperCase(Locale.ROOT));
  }
}
