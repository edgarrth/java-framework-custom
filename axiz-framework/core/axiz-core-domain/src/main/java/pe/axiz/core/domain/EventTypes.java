package pe.axiz.core.domain;

import java.util.Locale;

/** Utilidades para nombrar tipos de eventos. */
public final class EventTypes {

  private EventTypes() {}

  /** "OrderPlaced" se convierte en "order-placed"; se elimina el sufijo final "Event". */
  public static String kebab(String simpleName) {
    String name =
        simpleName.endsWith("Event") && simpleName.length() > 5
            ? simpleName.substring(0, simpleName.length() - 5)
            : simpleName;
    return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
  }
}
