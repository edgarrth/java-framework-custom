package pe.axiz.spring.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler.FirstMatchingCompositeObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.test.simple.SimpleSpan;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PublishResult;

/**
 * The relay publishes an outbox event outside the request that wrote it: the publication continues
 * the trace of the write (stored with the event), not the trace of the relay loop.
 */
class OutboxTraceRelayTest {

  static final String WRITE_TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
  static final String WRITE_SPAN = "00f067aa0ba902b7";

  private static AxizMessagingProperties properties() {
    return new AxizMessagingProperties(
        null,
        "",
        Map.of("orders", "kafka:orders"),
        new AxizMessagingProperties.Consumer(
            null,
            5,
            Duration.ofSeconds(1),
            Duration.ofMinutes(5),
            false,
            List.of(),
            Duration.ofSeconds(10),
            Duration.ofMinutes(10)),
        new AxizMessagingProperties.Kafka(
            null, false, List.of(), null, 1, ".dlq", Duration.ofSeconds(10)),
        new AxizMessagingProperties.Sqs(
            List.of(), Map.of(), Duration.ofSeconds(20), 10, Duration.ofSeconds(60)));
  }

  private static EventEnvelope fromOutbox() {
    return new EventEnvelope(
        "e-1",
        "urn:axiz:orders",
        "pe.axiz.orders.order-placed.v1",
        Instant.EPOCH,
        "o-1",
        "application/json",
        "{}".getBytes(StandardCharsets.UTF_8),
        Map.of(
            EventEnvelope.OUTBOX_TRACEPARENT,
            "00-" + WRITE_TRACE + "-" + WRITE_SPAN + "-01",
            EventEnvelope.CORRELATION_ID,
            "corr-1"));
  }

  @Test
  void thePublicationContinuesTheTraceOfTheWrite() {
    SimpleTracer tracer = new SimpleTracer();
    W3cTestPropagator propagator = new W3cTestPropagator(tracer);
    ObservationRegistry registry = ObservationRegistry.create();
    registry
        .observationConfig()
        .observationHandler(
            new FirstMatchingCompositeObservationHandler(
                new PropagatingSenderTracingObservationHandler<>(tracer, propagator),
                new PropagatingReceiverTracingObservationHandler<>(tracer, propagator),
                new DefaultTracingObservationHandler(tracer)));
    List<EventEnvelope> sent = new ArrayList<>();
    List<String> destinations = new ArrayList<>();
    EventPublisher broker =
        new EventPublisher() {
          @Override
          public PublishResult publish(String destination, EventEnvelope event) {
            destinations.add(destination);
            sent.add(event);
            return new PublishResult(event.id(), destination, null);
          }
        };
    ObservedEventPublisher publisher = new ObservedEventPublisher(broker, registry, properties());

    // the relay loop has an observation of its own (one per batch), current while it publishes
    Observation.createNotStarted("axiz.outbox.relay", registry)
        .observe(() -> publisher.publish("orders", fromOutbox()));

    EventEnvelope published = sent.getFirst();
    assertEquals("kafka:orders", destinations.getFirst());
    assertFalse(published.extension(EventEnvelope.OUTBOX_TRACEPARENT).isPresent());
    assertEquals("corr-1", published.correlationId().orElseThrow());

    SimpleSpan dispatch =
        tracer.getSpans().stream()
            .filter(s -> "outbox dispatch orders".equals(s.getName()))
            .findFirst()
            .orElseThrow();
    SimpleSpan publish =
        tracer.getSpans().stream()
            .filter(s -> "publish orders".equals(s.getName()))
            .findFirst()
            .orElseThrow();
    // dispatch continues the write; the publication is its child; the consumer gets the publication
    assertEquals(WRITE_TRACE, dispatch.context().traceId());
    assertEquals(WRITE_SPAN, dispatch.context().parentId());
    assertEquals(WRITE_TRACE, publish.context().traceId());
    assertEquals(dispatch.context().spanId(), publish.context().parentId());
    assertEquals(
        "00-" + WRITE_TRACE + "-" + publish.context().spanId() + "-01",
        published.extension(EventEnvelope.TRACEPARENT).orElseThrow());
  }

  @Test
  void eventsWithoutAStoredTraceArePublishedAsBefore() {
    List<EventEnvelope> sent = new ArrayList<>();
    EventPublisher broker =
        new EventPublisher() {
          @Override
          public PublishResult publish(String destination, EventEnvelope event) {
            sent.add(event);
            return new PublishResult(event.id(), destination, null);
          }
        };
    EventEnvelope plain = fromOutbox().withoutExtensions(EventEnvelope.OUTBOX_TRACEPARENT);
    new ObservedEventPublisher(broker, ObservationRegistry.NOOP, properties())
        .publish("orders", plain);
    assertTrue(
        sent.getFirst().extensions().keySet().stream().noneMatch(k -> k.startsWith("outbox")));
  }
}
