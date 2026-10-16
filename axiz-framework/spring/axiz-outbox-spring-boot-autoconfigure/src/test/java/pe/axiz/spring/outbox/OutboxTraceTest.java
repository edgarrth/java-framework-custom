package pe.axiz.spring.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler.FirstMatchingCompositeObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.test.simple.SimpleSpan;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.messaging.EventEnvelope;

class OutboxTraceTest {

  private static EventEnvelope event() {
    return new EventEnvelope(
        "e-1",
        "urn:axiz:orders",
        "pe.axiz.orders.order-placed.v1",
        Instant.EPOCH,
        "o-1",
        "application/json",
        "{}".getBytes(StandardCharsets.UTF_8),
        Map.of());
  }

  private static ObservationRegistry tracing(SimpleTracer tracer) {
    W3cTestPropagator propagator = new W3cTestPropagator(tracer);
    ObservationRegistry registry = ObservationRegistry.create();
    registry
        .observationConfig()
        .observationHandler(
            new FirstMatchingCompositeObservationHandler(
                new PropagatingSenderTracingObservationHandler<>(tracer, propagator),
                new PropagatingReceiverTracingObservationHandler<>(tracer, propagator),
                new DefaultTracingObservationHandler(tracer)));
    return registry;
  }

  @Test
  void theWriteKeepsTheTraceOfTheRequest() {
    SimpleTracer tracer = new SimpleTracer();
    ObservationRegistry registry = tracing(tracer);
    Observation request = Observation.start("http.server.requests", registry);
    List<EventEnvelope> stored = new ArrayList<>();
    try (Observation.Scope ignored = request.openScope()) {
      // what OutboxWriter does for TransactionalEvents, durable audit and every other writer
      OutboxTrace.tracing(registry).traced("orders", event(), stored::add);
    }
    request.stop();
    SimpleSpan requestSpan = tracer.getSpans().getFirst();
    SimpleSpan writeSpan = tracer.getSpans().getLast();
    // the write span is a child of the request, and its context travels with the stored event
    assertEquals(requestSpan.context().spanId(), writeSpan.context().parentId());
    assertEquals(
        "00-" + requestSpan.context().traceId() + "-" + writeSpan.context().spanId() + "-01",
        stored.getFirst().extension(EventEnvelope.OUTBOX_TRACEPARENT).orElseThrow());
    // the regular traceparent belongs to the publication, written by the relay
    assertFalse(stored.getFirst().extension(EventEnvelope.TRACEPARENT).isPresent());
    assertEquals("outbox write orders", writeSpan.getName());
  }

  @Test
  void withoutATracerNothingIsStored() {
    OutboxTrace.Write write = OutboxTrace.start(ObservationRegistry.NOOP, "orders", event(), null);
    EventEnvelope e = event();
    assertSame(e, write.stamp(e));
    write.observation().stop();
  }
}
