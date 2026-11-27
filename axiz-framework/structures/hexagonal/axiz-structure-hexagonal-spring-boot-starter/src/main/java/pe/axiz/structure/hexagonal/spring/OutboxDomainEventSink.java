package pe.axiz.structure.hexagonal.spring;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import pe.axiz.core.domain.DomainEvent;
import pe.axiz.spring.outbox.TransactionalEvents;
import pe.axiz.structure.hexagonal.AggregateEvent;
import pe.axiz.structure.hexagonal.DomainEventSink;

/**
 * Escribe los eventos de dominio en el outbox dentro de la transacción del caso de uso, de modo que
 * se publican si y solo si el cambio de negocio hace commit. El id del evento se convierte en el id
 * de CloudEvents.
 */
public final class OutboxDomainEventSink implements DomainEventSink {

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "TransactionalEvents is an injected collaborator whose identity and lifecycle must "
              + "be shared, not copied.")
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
