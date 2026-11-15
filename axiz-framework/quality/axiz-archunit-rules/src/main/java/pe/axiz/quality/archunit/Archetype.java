package pe.axiz.quality.archunit;

import java.util.Locale;

/** Service archetype. */
public enum Archetype {
  BUSINESS,
  CORE,
  GATEWAY,
  UX,
  SUPPORT,
  EVENT_PROCESSOR,
  BATCH;

  public static Archetype parse(String value) {
    return valueOf(value.strip().replace('-', '_').toUpperCase(Locale.ROOT));
  }
}
