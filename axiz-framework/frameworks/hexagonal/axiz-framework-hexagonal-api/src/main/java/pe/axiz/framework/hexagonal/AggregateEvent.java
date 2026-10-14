package pe.axiz.framework.hexagonal;

import pe.axiz.core.domain.DomainEvent;

/** A domain event that names its aggregate type, used for routing and partitioning. */
public interface AggregateEvent extends DomainEvent {

  /** Aggregate type, e.g. {@code payment}. */
  String aggregateType();
}
