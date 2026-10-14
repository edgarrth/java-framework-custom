package pe.axiz.spring.messaging;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PublishResult;

/**
 * Entry point for publishing: builds the CloudEvents envelope with the current request context and
 * publishes it, confirming the broker acknowledgement or failing visibly. For publication in the
 * same transaction as a database change use the outbox starter.
 */
public class AxizEvents {

  private final EventFactory factory;
  private final EventPublisher publisher;

  public AxizEvents(EventFactory factory, EventPublisher publisher) {
    this.factory = Objects.requireNonNull(factory);
    this.publisher = Objects.requireNonNull(publisher);
  }

  /** Builds an envelope; {@code eventId} should be the domain event id when there is one. */
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
