package pe.axiz.core.domain;

import java.util.Locale;

/** Event type naming helpers. */
public final class EventTypes {

  private EventTypes() {}

  /** "OrderPlaced" becomes "order-placed"; a trailing "Event" suffix is removed. */
  public static String kebab(String simpleName) {
    String name =
        simpleName.endsWith("Event") && simpleName.length() > 5
            ? simpleName.substring(0, simpleName.length() - 5)
            : simpleName;
    return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
  }
}
