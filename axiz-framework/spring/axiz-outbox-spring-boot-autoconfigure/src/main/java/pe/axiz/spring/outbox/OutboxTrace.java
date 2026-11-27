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
 * Conserva la traza de la solicitud que escribe un evento en el outbox. La escritura se ejecuta en
 * una observación {@code axiz.outbox.write} cuyo contexto el tracer inyecta en un portador; el
 * {@code traceparent} y el {@code tracestate} de W3C se almacenan con el evento ({@link
 * EventEnvelope#OUTBOX_TRACEPARENT}), y el relay continúa esa traza cuando publica el evento más
 * tarde, fuera de la solicitud. Sin tracer, la observación no hace nada y no se almacena nada.
 */
final class OutboxTrace {

  private OutboxTrace() {}

  /** Tracing del {@link pe.axiz.core.outbox.OutboxWriter} bloqueante. */
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

  /** Observación de escritura iniciada; {@link #carrier()} contiene el contexto inyectado. */
  record Write(Observation observation, Map<String, String> carrier) {

    /** El sobre con el contexto de traza de esta escritura, cuando existe. */
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
   * Inicia la observación de escritura de un evento, hija de {@code parent} (o de la observación
   * actual cuando {@code parent} es null).
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
