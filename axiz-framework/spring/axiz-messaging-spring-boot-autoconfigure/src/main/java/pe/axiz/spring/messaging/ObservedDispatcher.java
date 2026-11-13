package pe.axiz.spring.messaging;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
import pe.axiz.spring.foundation.DispatchBoundary;

/**
 * Runs the dispatcher inside a CONSUMER observation continuing the producer's trace, with the
 * request context (correlation id, tenant, channel) restored from the envelope.
 */
public class ObservedDispatcher {

  private final EventDispatcher dispatcher;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "ObservationRegistry is an injected collaborator whose identity and lifecycle must "
              + "be shared, not copied.")
  private final ObservationRegistry registry;

  private final Clock clock;
  private final DispatchBoundary boundary;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Rejects invalid construction; no finalizer or publication of this. The overridable "
              + "Spring SPI remains extensible; this constructor acquires no external resource.")
  public ObservedDispatcher(EventDispatcher dispatcher, ObservationRegistry registry, Clock clock) {
    this(dispatcher, registry, clock, () -> () -> {});
  }

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Observation extension point validates borrowed collaborators; construction "
              + "acquires no external resources.")
  public ObservedDispatcher(
      EventDispatcher dispatcher,
      ObservationRegistry registry,
      Clock clock,
      DispatchBoundary boundary) {
    this.boundary = Objects.requireNonNull(boundary);
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
    try (RequestContext.Scope ignored = RequestContext.open(metadata);
        var completion = boundary.open()) {
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
