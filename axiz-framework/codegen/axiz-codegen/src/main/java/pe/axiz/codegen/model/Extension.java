package pe.axiz.codegen.model;

import java.util.Locale;

/** Extensión opcional de una estructura de servicio compatible. */
public enum Extension {
  CQRS;

  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static Extension of(String value) {
    return valueOf(value.toUpperCase(Locale.ROOT));
  }

  public String starter() {
    return "axiz-extension-" + id() + "-spring-boot-starter";
  }
}
