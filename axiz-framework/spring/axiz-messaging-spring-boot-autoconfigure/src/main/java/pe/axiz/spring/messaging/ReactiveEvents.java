package pe.axiz.spring.messaging;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.messaging.AsyncEventPublisher;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.PublishResult;
import pe.axiz.spring.foundation.ReactiveStages;
import reactor.core.publisher.Mono;

/**
 * Publicación nativa diferida de eventos con metadatos del suscriptor, confirmación y cancelación.
 */
public final class ReactiveEvents {
  private final EventFactory factory;
  private final AsyncEventPublisher publisher;

  public ReactiveEvents(EventFactory factory, AsyncEventPublisher publisher) {
    this.factory = Objects.requireNonNull(factory);
    this.publisher = Objects.requireNonNull(publisher);
  }

  public Mono<EventEnvelope> envelope(
      @Nullable String eventId, String type, @Nullable String subject, Object payload) {
    return Mono.deferContextual(
        context ->
            Mono.fromSupplier(
                () ->
                    factory.create(
                        eventId,
                        type,
                        subject,
                        payload,
                        context.getOrDefault(RequestContext.KEY, null))));
  }

  public Mono<PublishResult> publish(
      String destination, String type, @Nullable String subject, Object payload) {
    return envelope(null, type, subject, payload).flatMap(event -> publish(destination, event));
  }

  public Mono<PublishResult> publish(String destination, EventEnvelope event) {
    return ReactiveStages.from(() -> publisher.publish(destination, event));
  }
}
