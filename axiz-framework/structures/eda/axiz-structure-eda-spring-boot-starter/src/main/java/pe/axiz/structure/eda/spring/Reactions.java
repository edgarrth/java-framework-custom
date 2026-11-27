package pe.axiz.structure.eda.spring;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventRoutes;
import pe.axiz.spring.outbox.TransactionalEvents;

/**
 * Emite eventos en reacción a uno consumido (coreografía) mediante el outbox, dentro de la
 * transacción del handler. El id de correlación del contexto de la petición se propaga de forma
 * automática.
 */
public final class Reactions {

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "TransactionalEvents is an injected collaborator whose identity and lifecycle must "
              + "be shared, not copied.")
  private final TransactionalEvents events;

  private final EventRoutes routes;

  public Reactions(TransactionalEvents events, EventRoutes routes) {
    this.events = Objects.requireNonNull(events);
    this.routes = Objects.requireNonNull(routes);
  }

  /**
   * Encola una reacción.
   *
   * @param type tipo de evento sin prefijo, por ejemplo {@code ledger-entry-posted.v1}
   * @param aggregateType tipo de agregado
   * @param aggregateId id del agregado
   * @param payload payload
   * @param eventId id determinista (por ejemplo, derivado del evento consumido) para que las
   *     reentregas no dupliquen la reacción aguas abajo
   * @return el envelope almacenado
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
