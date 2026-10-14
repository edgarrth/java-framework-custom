package pe.axiz.codegen.model;

import java.util.Locale;

/** Web stack of a service. */
public enum Stack {
  SERVLET,
  REACTIVE;

  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static Stack of(String id) {
    return valueOf(id.toUpperCase(Locale.ROOT));
  }
}
