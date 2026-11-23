package pe.axiz.structure.hexagonal;

import java.util.List;
import pe.axiz.core.domain.DomainEvent;

/**
 * Destino de los eventos de dominio cuando termina un caso de uso. Un sink transaccional (outbox)
 * los recibe dentro de la transacción; cualquier otro sink los recibe tras un commit exitoso.
 */
public interface DomainEventSink {

  void publish(List<DomainEvent> events);

  /** Indica si {@link #publish} debe ejecutarse dentro de la transacción del caso de uso. */
  default boolean transactional() {
    return false;
  }

  static DomainEventSink discard() {
    return events -> {};
  }
}
