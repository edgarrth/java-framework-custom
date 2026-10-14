package pe.axiz.core.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Emits structured events to the {@code axiz.event} logger and to the current observation. */
public final class LoggingStructuredEventEmitter implements StructuredEventEmitter {

  private static final Logger LOG = LoggerFactory.getLogger("axiz.event");

  private final ObservationRegistry registry;
  private final AttributeSanitizer sanitizer;

  public LoggingStructuredEventEmitter(ObservationRegistry registry, AttributeSanitizer sanitizer) {
    this.registry = Objects.requireNonNull(registry);
    this.sanitizer = Objects.requireNonNull(sanitizer);
  }

  @Override
  public void emit(StructuredEvent event) {
    var attributes = sanitizer.sanitize(event.attributes());
    if (event.kind() == StructuredEvent.Kind.ERROR) {
      LOG.warn("event.kind={} event.name={} attributes={}", event.kind(), event.name(), attributes);
    } else {
      LOG.info("event.kind={} event.name={} attributes={}", event.kind(), event.name(), attributes);
    }
    Observation current = registry.getCurrentObservation();
    if (current != null) {
      current.event(Observation.Event.of(event.name(), event.kind() + " " + attributes));
    }
  }
}
