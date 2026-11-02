package pe.axiz.structure.hexagonal.spring;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import pe.axiz.core.domain.DomainEvent;
import pe.axiz.spring.outbox.TransactionalEvents;
import pe.axiz.structure.hexagonal.AggregateEvent;
import pe.axiz.structure.hexagonal.DomainEventSink;

/**
 * Writes domain events to the outbox in the use case transaction, so they are published if and only
 * if the business change commits. The event id becomes the CloudEvents id.
 */
public class OutboxDomainEventSink implements DomainEventSink {

  private final TransactionalEvents events;
  private final String defaultDestination;
  private final Map<String, String> destinations;
  private final String typeSuffix;

  public OutboxDomainEventSink(
      TransactionalEvents events,
      String defaultDestination,
      Map<String, String> destinations,
      String typeSuffix) {
    this.events = Objects.requireNonNull(events);
    this.defaultDestination = Objects.requireNonNull(defaultDestination);
    this.destinations = Map.copyOf(destinations);
    this.typeSuffix = Objects.requireNonNull(typeSuffix);
  }

  @Override
  public void publish(List<DomainEvent> domainEvents) {
    for (DomainEvent e : domainEvents) {
      String aggregateType = e instanceof AggregateEvent a ? a.aggregateType() : "aggregate";
      String destination = destinations.getOrDefault(e.eventType(), defaultDestination);
      events.enqueue(
          destination, e.eventId(), e.eventType() + typeSuffix, aggregateType, e.aggregateId(), e);
    }
  }

  @Override
  public boolean transactional() {
    return true;
  }
}
