package pe.axiz.core.domain;

import java.time.Instant;

/**
 * Algo que ocurrió en el dominio. Los eventos son inmutables (normalmente records) y se publican
 * solo después de que la transacción que los produjo hace commit.
 */
public interface DomainEvent {

  /** Id único de este evento (UUIDv7); se usa para deduplicación aguas abajo. */
  String eventId();

  /** Momento en que ocurrió. */
  Instant occurredAt();

  /** Id del agregado que lo produjo. */
  String aggregateId();

  /**
   * Tipo de evento; por defecto, el nombre simple de la clase en kebab-case, por ejemplo {@code
   * order-placed}.
   */
  default String eventType() {
    return EventTypes.kebab(getClass().getSimpleName());
  }
}
