package pe.axiz.structure.hexagonal.spring;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import pe.axiz.core.domain.DomainEvent;
import pe.axiz.spring.outbox.ReactiveTransactionalEvents;
import pe.axiz.structure.hexagonal.AggregateEvent;
import pe.axiz.structure.hexagonal.ReactiveDomainEventSink;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Encola lotes de eventos inmutables de forma secuencial en la transacción R2DBC de quien llama.
 */
public final class ReactiveOutboxDomainEventSink implements ReactiveDomainEventSink {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "ReactiveTransactionalEvents is a borrowed collaborator; sharing its identity "
              + "preserves lifecycle, transactions or instrumentation. The field is not an owned "
              + "mutable value.")
  private final ReactiveTransactionalEvents events;

  private final String defaultDestination;
  private final Map<String, String> destinations;
  private final String typeSuffix;

  public ReactiveOutboxDomainEventSink(
      ReactiveTransactionalEvents events,
      String defaultDestination,
      Map<String, String> destinations,
      String typeSuffix) {
    this.events = Objects.requireNonNull(events);
    this.defaultDestination = Objects.requireNonNull(defaultDestination);
    this.destinations = Map.copyOf(destinations);
    this.typeSuffix = Objects.requireNonNull(typeSuffix);
  }

  @Override
  public Mono<Void> publish(Collection<? extends DomainEvent> domainEvents) {
    List<? extends DomainEvent> batch = List.copyOf(domainEvents);
    return Flux.fromIterable(batch)
        .concatMap(
            event ->
                Mono.defer(
                    () ->
                        events.enqueue(
                            destinations.getOrDefault(event.eventType(), defaultDestination),
                            event.eventId(),
                            event.eventType() + typeSuffix,
                            event instanceof AggregateEvent aggregate
                                ? aggregate.aggregateType()
                                : "aggregate",
                            event.aggregateId(),
                            event)))
        .then();
  }
}
