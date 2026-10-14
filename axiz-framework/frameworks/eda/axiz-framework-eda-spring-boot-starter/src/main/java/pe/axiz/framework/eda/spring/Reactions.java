package pe.axiz.framework.eda.spring;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.framework.eda.EventRoutes;
import pe.axiz.spring.outbox.TransactionalEvents;

/**
 * Emits events in reaction to a consumed one (choreography) through the outbox, inside the handler
 * transaction. The correlation id of the request context is propagated automatically.
 */
public class Reactions {

  private final TransactionalEvents events;
  private final EventRoutes routes;

  public Reactions(TransactionalEvents events, EventRoutes routes) {
    this.events = Objects.requireNonNull(events);
    this.routes = Objects.requireNonNull(routes);
  }

  /**
   * Enqueues a reaction.
   *
   * @param type event type without prefix, e.g. {@code ledger-entry-posted.v1}
   * @param aggregateType aggregate type
   * @param aggregateId aggregate id
   * @param payload payload
   * @param eventId deterministic id (e.g. derived from the consumed event) so redeliveries do not
   *     duplicate the reaction downstream
   * @return the stored envelope
   */
  public EventEnvelope emit(
      String type,
      String aggregateType,
      @Nullable String aggregateId,
      Object payload,
      String eventId) {
    return events.enqueue(
        routes.destinationOf(type), eventId, type, aggregateType, aggregateId, payload);
  }
}
