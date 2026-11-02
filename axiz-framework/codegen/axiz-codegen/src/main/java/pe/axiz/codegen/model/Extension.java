package pe.axiz.codegen.model;

import java.util.Locale;

/** Optional extension of a compatible service structure. */
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
