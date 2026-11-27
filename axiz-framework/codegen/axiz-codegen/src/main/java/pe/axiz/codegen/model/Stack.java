package pe.axiz.codegen.model;

import java.util.Locale;

/** Stack web de un servicio. */
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
