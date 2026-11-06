package pe.axiz.spring.messaging;

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
 * Publishes inside a PRODUCER observation. The tracer's propagator writes the trace context into
 * the CloudEvents distributed-tracing extensions ({@code traceparent}, {@code tracestate}), so it
 * travels the same way on MSK, SQS and SNS.
 */
public class ObservedEventPublisher implements EventPublisher, BoundedPublisher {

  private final EventPublisher delegate;
  private final ObservationRegistry registry;
  private final AxizMessagingProperties properties;

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
   * Publishes the event. An event that comes from the outbox carries the trace context of its write
   * ({@link EventEnvelope#OUTBOX_TRACEPARENT}): the publication then runs inside an {@code
   * axiz.outbox.dispatch} observation that continues that trace, so the request that wrote the
   * event, its publication and its consumers form one trace. That extension is removed before
   * publishing.
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
    return new EventEnvelope(
        event.id(),
        event.source(),
        event.type(),
        event.time(),
        event.subject(),
        event.dataContentType(),
        event.data(),
        ext);
  }
}
