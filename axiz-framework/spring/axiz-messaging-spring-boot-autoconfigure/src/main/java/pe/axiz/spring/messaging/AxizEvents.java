package pe.axiz.spring.messaging;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PublishResult;

/**
 * Punto de entrada para publicar: construye el sobre CloudEvents con el contexto de la solicitud
 * actual y lo publica, confirmando el acuse del broker o fallando de forma visible. Para publicar
 * en la misma transacción que un cambio en la base de datos se usa el starter de outbox.
 */
public final class AxizEvents {

  private final EventFactory factory;
  private final EventPublisher publisher;

  public AxizEvents(EventFactory factory, EventPublisher publisher) {
    this.factory = Objects.requireNonNull(factory);
    this.publisher = Objects.requireNonNull(publisher);
  }

  /** Construye un sobre; {@code eventId} debería ser el id del evento de dominio cuando existe. */
  public EventEnvelope envelope(
      @Nullable String eventId, String type, @Nullable String subject, Object payload) {
    return factory.create(eventId, type, subject, payload, RequestContext.get());
  }

  public PublishResult publish(
      String destination, String type, @Nullable String subject, Object payload) {
    return publisher.publish(destination, envelope(null, type, subject, payload));
  }

  public PublishResult publish(String destination, EventEnvelope envelope) {
    return publisher.publish(destination, envelope);
  }
}
