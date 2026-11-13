package pe.axiz.structure.eda.spring;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventRoutes;
import pe.axiz.spring.outbox.ReactiveTransactionalEvents;
import reactor.core.publisher.Mono;

/**
 * Reactive {@link Reactions}: emits events in reaction to a consumed one through the R2DBC outbox,
 * inside the reactive transaction of the handler.
 */
public final class ReactiveReactions {

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "ReactiveTransactionalEvents is an injected collaborator whose identity and "
              + "lifecycle must be shared, not copied.")
  private final ReactiveTransactionalEvents events;

  private final EventRoutes routes;

  public ReactiveReactions(ReactiveTransactionalEvents events, EventRoutes routes) {
    this.events = Objects.requireNonNull(events);
    this.routes = Objects.requireNonNull(routes);
  }

  /**
   * Enqueues a reaction when the returned {@code Mono} is subscribed.
   *
   * @param type event type without prefix
   * @param aggregateType aggregate type
   * @param aggregateId aggregate id
   * @param payload payload
   * @param eventId deterministic id, so redeliveries do not duplicate the reaction downstream
   * @return the stored envelope
   */
  public Mono<EventEnvelope> emit(
      String type,
      String aggregateType,
      @Nullable String aggregateId,
      Object payload,
      String eventId) {
    return events.enqueue(
        routes.destinationOf(type), eventId, type, aggregateType, aggregateId, payload);
  }
}
