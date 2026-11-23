package pe.axiz.structure.eda.spring;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventRoutes;
import pe.axiz.spring.outbox.ReactiveTransactionalEvents;
import reactor.core.publisher.Mono;

/**
 * {@link Reactions} reactivas: emite eventos en reacción a uno consumido mediante el outbox R2DBC,
 * dentro de la transacción reactiva del handler.
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
   * Encola una reacción cuando se suscribe el {@code Mono} devuelto.
   *
   * @param type tipo de evento sin prefijo
   * @param aggregateType tipo de agregado
   * @param aggregateId id del agregado
   * @param payload payload
   * @param eventId id determinista, para que las reentregas no dupliquen la reacción aguas abajo
   * @return el envelope almacenado
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
