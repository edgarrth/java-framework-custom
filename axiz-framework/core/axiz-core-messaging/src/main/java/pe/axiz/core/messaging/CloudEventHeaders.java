package pe.axiz.core.messaging;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * CloudEvents binary mode mapping to transport headers/attributes with a prefix ({@code ce_} for
 * Kafka and SQS/SNS attributes).
 */
public final class CloudEventHeaders {

  public static final String PREFIX = "ce_";

  private CloudEventHeaders() {}

  /** Headers for an envelope (data travels as the message body). */
  public static Map<String, String> toHeaders(EventEnvelope e) {
    Map<String, String> h = new LinkedHashMap<>();
    h.put(PREFIX + "specversion", EventEnvelope.SPEC_VERSION);
    h.put(PREFIX + "id", e.id());
    h.put(PREFIX + "source", e.source());
    h.put(PREFIX + "type", e.type());
    h.put(PREFIX + "time", e.time().toString());
    if (e.subject() != null) {
      h.put(PREFIX + "subject", e.subject());
    }
    h.put("content-type", e.dataContentType());
    e.extensions().forEach((k, v) -> h.put(PREFIX + k, v));
    return h;
  }

  /**
   * Rebuilds an envelope from transport headers and body.
   *
   * @param headers header lookup
   * @param names header names present
   * @param body message body
   * @return the envelope
   */
  public static EventEnvelope fromHeaders(
      Function<String, @Nullable String> headers, Iterable<String> names, byte[] body) {
    String id = required(headers, "id");
    String source = required(headers, "source");
    String type = required(headers, "type");
    String timeText = headers.apply(PREFIX + "time");
    Instant time;
    try {
      time = timeText == null ? Instant.EPOCH : Instant.parse(timeText);
    } catch (DateTimeParseException e) {
      throw new PayloadException("Invalid ce_time", e);
    }
    String contentType = headers.apply("content-type");
    Map<String, String> ext = new LinkedHashMap<>();
    for (String n : names) {
      String lower = n.toLowerCase(Locale.ROOT);
      if (lower.startsWith(PREFIX)) {
        String key = lower.substring(PREFIX.length());
        if (!Map.of("specversion", 1, "id", 1, "source", 1, "type", 1, "time", 1, "subject", 1)
            .containsKey(key)) {
          String v = headers.apply(n);
          if (v != null) {
            ext.put(key, v);
          }
        }
      }
    }
    return new EventEnvelope(
        id,
        source,
        type,
        time,
        headers.apply(PREFIX + "subject"),
        contentType == null ? "application/json" : contentType,
        body,
        ext);
  }

  private static String required(Function<String, @Nullable String> headers, String name) {
    String v = headers.apply(PREFIX + name);
    if (v == null || v.isBlank()) {
      throw new PayloadException(
          "Missing CloudEvents attribute " + name, new IllegalArgumentException(name));
    }
    return v;
  }
}
