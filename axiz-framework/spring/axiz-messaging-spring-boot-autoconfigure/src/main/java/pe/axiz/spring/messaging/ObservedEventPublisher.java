package pe.axiz.spring.messaging;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.observation.transport.SenderContext;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import pe.axiz.core.messaging.BoundedPublisher;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PublishBound;
import pe.axiz.core.messaging.PublishResult;
import pe.axiz.core.observability.AxizAttributes;
import pe.axiz.core.observability.AxizObservations;

/**
 * Publica dentro de una observación PRODUCER. El propagador del tracer escribe el contexto de traza
 * en las extensiones de trazado distribuido de CloudEvents ({@code traceparent}, {@code
 * tracestate}), de modo que viaja de la misma forma en MSK, SQS y SNS.
 */
public final class ObservedEventPublisher implements EventPublisher, BoundedPublisher {

  private final EventPublisher delegate;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "ObservationRegistry is an injected collaborator whose identity and lifecycle must "
              + "be shared, not copied.")
  private final ObservationRegistry registry;

  private final AxizMessagingProperties properties;

  @Override
  public boolean supports(String destination) {
    return delegate.supports(properties.resolve(destination));
  }

  @Override
  public PublishBound publishBound() {
    return BoundedPublisher.of(delegate);
  }

  public ObservedEventPublisher(
      EventPublisher delegate, ObservationRegistry registry, AxizMessagingProperties properties) {
    this.delegate = Objects.requireNonNull(delegate);
    this.registry = Objects.requireNonNull(registry);
    this.properties = Objects.requireNonNull(properties);
  }

  /**
   * Publica el evento. Un evento que proviene del outbox lleva el contexto de traza de su escritura
   * ({@link EventEnvelope#OUTBOX_TRACEPARENT}): la publicación se ejecuta entonces dentro de una
   * observación {@code axiz.outbox.dispatch} que continúa esa traza, de modo que la solicitud que
   * escribió el evento, su publicación y sus consumidores forman una sola traza. Esa extensión se
   * elimina antes de publicar.
   */
  @Override
  public PublishResult publish(String destination, EventEnvelope event) {
    String stored = event.extension(EventEnvelope.OUTBOX_TRACEPARENT).orElse(null);
    if (stored == null) {
      return send(destination, event);
    }
    Map<String, String> carrier = new HashMap<>();
    carrier.put(EventEnvelope.TRACEPARENT, stored);
    event
        .extension(EventEnvelope.OUTBOX_TRACESTATE)
        .ifPresent(v -> carrier.put(EventEnvelope.TRACESTATE, v));
    ReceiverContext<Map<String, String>> context =
        new ReceiverContext<>((c, k) -> c == null ? null : c.get(k));
    context.setCarrier(carrier);
    context.setRemoteServiceName("outbox");
    EventEnvelope clean =
        event.withoutExtensions(EventEnvelope.OUTBOX_TRACEPARENT, EventEnvelope.OUTBOX_TRACESTATE);
    return Observation.createNotStarted(AxizObservations.OUTBOX_DISPATCH, () -> context, registry)
        .contextualName("outbox dispatch " + destination)
        .lowCardinalityKeyValue(KeyValue.of(AxizAttributes.DESTINATION, destination))
        .lowCardinalityKeyValue(KeyValue.of(AxizAttributes.EVENT_TYPE, event.type()))
        .highCardinalityKeyValue(KeyValue.of(AxizAttributes.EVENT_ID, event.id()))
        .observe(() -> send(destination, clean));
  }

  private PublishResult send(String destination, EventEnvelope event) {
    String physical = properties.resolve(destination);
    Map<String, String> carrier = new HashMap<>();
    SenderContext<Map<String, String>> context =
        new SenderContext<>(
            (c, k, v) -> {
              if (c != null) {
                c.put(k, v);
              }
            });
    context.setCarrier(carrier);
    context.setRemoteServiceName(destination);
    Observation observation =
        Observation.createNotStarted(AxizObservations.EVENT_PUBLISH, () -> context, registry)
            .contextualName("publish " + destination)
            .lowCardinalityKeyValue(KeyValue.of(AxizAttributes.DESTINATION, destination))
            .lowCardinalityKeyValue(KeyValue.of(AxizAttributes.EVENT_TYPE, event.type()))
            .highCardinalityKeyValue(KeyValue.of(AxizAttributes.EVENT_ID, event.id()));
    return observation.observe(() -> delegate.publish(physical, withTrace(event, carrier)));
  }

  static EventEnvelope withTrace(EventEnvelope event, Map<String, String> carrier) {
    if (carrier.isEmpty()) {
      return event;
    }
    Map<String, String> ext = new LinkedHashMap<>(event.extensions());
    for (String key : new String[] {EventEnvelope.TRACEPARENT, EventEnvelope.TRACESTATE}) {
      String v = carrier.get(key);
      if (v != null) {
        ext.put(key, v);
      }
    }
    return EventEnvelope.builder()
        .id(event.id())
        .source(event.source())
        .type(event.type())
        .time(event.time())
        .subject(event.subject())
        .dataContentType(event.dataContentType())
        .data(event.data())
        .extensions(ext)
        .build();
  }
}
