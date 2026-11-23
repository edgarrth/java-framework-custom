package pe.axiz.structure.hexagonal;

import pe.axiz.core.domain.DomainEvent;

/** Evento de dominio que nombra su tipo de agregado, usado para enrutamiento y particionado. */
public interface AggregateEvent extends DomainEvent {

  /** Tipo de agregado, por ejemplo {@code payment}. */
  String aggregateType();
}
