package pe.axiz.core.observability;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Evento estructurado escrito como línea de log JSON y como evento en el span actual.
 *
 * @param kind tipo de evento
 * @param name nombre del evento, por ejemplo {@code order.created}
 * @param attributes atributos saneados
 * @param at instante
 */
public record StructuredEvent(Kind kind, String name, Map<String, String> attributes, Instant at) {

  /** Tipos de evento. */
  public enum Kind {
    BUSINESS,
    EXTERNAL_CALL,
    ERROR
  }

  public StructuredEvent {
    Objects.requireNonNull(kind);
    Objects.requireNonNull(name);
    attributes = Map.copyOf(attributes);
    Objects.requireNonNull(at);
  }
}
