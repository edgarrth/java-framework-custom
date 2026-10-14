package pe.axiz.spring.messaging;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.MDC;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.id.CorrelationIds;
import pe.axiz.core.messaging.EventDispatcher;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.observability.AxizAttributes;
import pe.axiz.core.observability.AxizObservations;

/**
 * Runs the dispatcher inside a CONSUMER observation continuing the producer's trace, with the
 * request context (correlation id, tenant, channel) restored from the envelope.
 */
public class ObservedDispatcher {

  private final EventDispatcher dispatcher;
  private final ObservationRegistry registry;
  private final Clock clock;

  public ObservedDispatcher(EventDispatcher dispatcher, ObservationRegistry registry, Clock clock) {
    this.dispatcher = Objects.requireNonNull(dispatcher);
    this.registry = Objects.requireNonNull(registry);
    this.clock = Objects.requireNonNull(clock);
  }

  /** Dispatches one delivery; deferred deliveries carry the delay before the redelivery. */
  public EventDispatcher.Outcome dispatch(String source, EventEnvelope envelope, int attempt) {
    ReceiverContext<EventEnvelope> context =
        new ReceiverContext<>((e, key) -> e != null ? e.extensions().get(key) : null);
    context.setCarrier(envelope);
    context.setRemoteServiceName(source);
    String correlationId = CorrelationIds.reuseOrCreate(envelope.correlationId().orElse(null));
    RequestMetadata metadata =
        RequestMetadata.builder(correlationId, envelope.id())
            .channel(envelope.extension("channel").orElse(null))
            .tenantId(envelope.extension(EventEnvelope.TENANT).orElse(null))
            .locale(Locale.ROOT)
            .receivedAt(clock.instant())
            .build();
    MDC.put("correlationId", correlationId);
    try (RequestContext.Scope ignored = RequestContext.open(metadata)) {
      return Observation.createNotStarted(AxizObservations.EVENT_HANDLER, () -> context, registry)
          .contextualName("process " + envelope.type())
          .lowCardinalityKeyValue(KeyValue.of(AxizAttributes.EVENT_TYPE, envelope.type()))
          .highCardinalityKeyValue(KeyValue.of(AxizAttributes.EVENT_ID, envelope.id()))
          .highCardinalityKeyValue(
              KeyValue.of("messaging.delivery.attempt", Integer.toString(attempt)))
          .observe(() -> dispatcher.dispatchWithDelay(envelope, attempt));
    } finally {
      MDC.remove("correlationId");
    }
  }
}
