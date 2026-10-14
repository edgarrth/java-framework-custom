package pe.axiz.core.observability;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Structured event written as a JSON log line and as an event on the current span.
 *
 * @param kind kind of event
 * @param name event name, e.g. {@code order.created}
 * @param attributes sanitized attributes
 * @param at instant
 */
public record StructuredEvent(Kind kind, String name, Map<String, String> attributes, Instant at) {

  /** Event kinds. */
  public enum Kind {
    BUSINESS,
    EXTERNAL_CALL,
    ERROR
  }

  public StructuredEvent {
    Objects.requireNonNull(kind);
    Objects.requireNonNull(name);
    attributes = Map.copyOf(attributes);
    Objects.requireNonNull(at);
  }
}
