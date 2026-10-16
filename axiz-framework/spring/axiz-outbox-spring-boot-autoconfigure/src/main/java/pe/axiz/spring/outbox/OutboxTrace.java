package pe.axiz.spring.outbox;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.SenderContext;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.observability.AxizAttributes;
import pe.axiz.core.observability.AxizObservations;
import pe.axiz.core.outbox.OutboxWriteTracing;

/**
 * Keeps the trace of the request that writes an event to the outbox. The write runs in an {@code
 * axiz.outbox.write} observation whose context the tracer injects into a carrier; the W3C {@code
 * traceparent} and {@code tracestate} are stored with the event ({@link
 * EventEnvelope#OUTBOX_TRACEPARENT}), and the relay continues that trace when it publishes the
 * event later, outside the request. Without a tracer the observation is a no-op and nothing is
 * stored.
 */
final class OutboxTrace {

  private OutboxTrace() {}

  /** Tracing of the blocking {@link pe.axiz.core.outbox.OutboxWriter}. */
  static OutboxWriteTracing tracing(ObservationRegistry registry) {
    return (destination, event, write) -> {
      Write w = start(registry, destination, event, null);
      try (Observation.Scope ignored = w.observation().openScope()) {
        write.accept(w.stamp(event));
      } catch (RuntimeException e) {
        w.observation().error(e);
        throw e;
      } finally {
        w.observation().stop();
      }
    };
  }

  /** A started write observation; {@link #carrier()} holds the injected context. */
  record Write(Observation observation, Map<String, String> carrier) {

    /** The envelope with the trace context of this write, when there is one. */
    EventEnvelope stamp(EventEnvelope envelope) {
      EventEnvelope e = envelope;
      String parent = carrier.get(EventEnvelope.TRACEPARENT);
      if (parent != null) {
        e = e.withExtension(EventEnvelope.OUTBOX_TRACEPARENT, parent);
        String state = carrier.get(EventEnvelope.TRACESTATE);
        if (state != null && !state.isBlank()) {
          e = e.withExtension(EventEnvelope.OUTBOX_TRACESTATE, state);
        }
      }
      return e;
    }
  }

  /**
   * Starts the write observation of an event, child of {@code parent} (or of the current
   * observation when {@code parent} is null).
   */
  static Write start(
      ObservationRegistry registry,
      String destination,
      EventEnvelope envelope,
      @Nullable Observation parent) {
    Map<String, String> carrier = new HashMap<>();
    SenderContext<Map<String, String>> context =
        new SenderContext<>(
            (c, k, v) -> {
              if (c != null) {
                c.put(k, v);
              }
            });
    context.setCarrier(carrier);
    context.setRemoteServiceName("outbox");
    Observation observation =
        Observation.createNotStarted(AxizObservations.OUTBOX_WRITE, () -> context, registry)
            .contextualName("outbox write " + destination)
            .lowCardinalityKeyValue(KeyValue.of(AxizAttributes.DESTINATION, destination))
            .lowCardinalityKeyValue(KeyValue.of(AxizAttributes.EVENT_TYPE, envelope.type()))
            .highCardinalityKeyValue(KeyValue.of(AxizAttributes.EVENT_ID, envelope.id()));
    if (parent != null) {
      observation.parentObservation(parent);
    }
    return new Write(observation.start(), carrier);
  }
}
