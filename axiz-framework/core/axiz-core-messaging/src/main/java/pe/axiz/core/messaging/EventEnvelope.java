package pe.axiz.core.messaging;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * CloudEvents 1.0 envelope. {@code data} is the encoded payload (JSON by default); extensions carry
 * correlation, tracing, tenant and declared business headers.
 *
 * @param id event id, also used for deduplication (FIFO, inbox)
 * @param source URI identifying the producer, e.g. {@code urn:axiz:payments-business}
 * @param type event type, e.g. {@code pe.axiz.payments.order-placed.v1}
 * @param time occurrence time
 * @param subject subject (aggregate id), used as partition / group key
 * @param dataContentType content type of {@code data}
 * @param data encoded payload
 * @param extensions CloudEvents extensions (lowercase alphanumeric names)
 */
public record EventEnvelope(
    String id,
    String source,
    String type,
    Instant time,
    @Nullable String subject,
    String dataContentType,
    byte[] data,
    Map<String, String> extensions) {

  public static final String SPEC_VERSION = "1.0";
  public static final String CORRELATION_ID = "correlationid";
  public static final String TRACEPARENT = "traceparent";
  public static final String TRACESTATE = "tracestate";

  /**
   * Trace context (W3C {@code traceparent}) of the outbox write that stored the event. The relay
   * publishes the event later, outside the request: it continues this trace and removes the
   * extension, so it never reaches the broker.
   */
  public static final String OUTBOX_TRACEPARENT = "outboxtraceparent";

  /** {@code tracestate} that goes with {@link #OUTBOX_TRACEPARENT}. */
  public static final String OUTBOX_TRACESTATE = "outboxtracestate";

  public static final String TENANT = "tenant";
  private static final Pattern EXTENSION_NAME = Pattern.compile("^[a-z0-9]{1,20}$");

  public EventEnvelope {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(time, "time");
    Objects.requireNonNull(dataContentType, "dataContentType");
    data = Objects.requireNonNull(data, "data").clone();
    for (String name : extensions.keySet()) {
      if (!EXTENSION_NAME.matcher(name).matches()) {
        throw new IllegalArgumentException("Invalid CloudEvents extension name: " + name);
      }
    }
    extensions = Map.copyOf(extensions);
  }

  @Override
  public byte[] data() {
    return data.clone();
  }

  public Optional<String> extension(String name) {
    return Optional.ofNullable(extensions.get(name.toLowerCase(Locale.ROOT)));
  }

  public Optional<String> correlationId() {
    return extension(CORRELATION_ID);
  }

  /** Copy with an extra extension. */
  public EventEnvelope withExtension(String name, String value) {
    Map<String, String> ext = new LinkedHashMap<>(extensions);
    ext.put(name.toLowerCase(Locale.ROOT), value);
    return new EventEnvelope(id, source, type, time, subject, dataContentType, data, ext);
  }

  /** Copy without the given extensions. */
  public EventEnvelope withoutExtensions(String... names) {
    Map<String, String> ext = new LinkedHashMap<>(extensions);
    for (String n : names) {
      ext.remove(n.toLowerCase(Locale.ROOT));
    }
    return ext.size() == extensions.size()
        ? this
        : new EventEnvelope(id, source, type, time, subject, dataContentType, data, ext);
  }

  @Override
  public boolean equals(@Nullable Object o) {
    return o instanceof EventEnvelope e && id.equals(e.id) && source.equals(e.source);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, source);
  }

  @Override
  public String toString() {
    return "EventEnvelope[id="
        + id
        + ", type="
        + type
        + ", source="
        + source
        + ", subject="
        + subject
        + "]";
  }
}
