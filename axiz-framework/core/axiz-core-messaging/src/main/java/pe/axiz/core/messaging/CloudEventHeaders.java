package pe.axiz.core.messaging;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Mapeo del modo binario de CloudEvents a cabeceras o atributos de transporte con un prefijo
 * ({@code ce_} para Kafka y atributos de SQS/SNS).
 */
public final class CloudEventHeaders {

  public static final String PREFIX = "ce_";

  private CloudEventHeaders() {}

  /** Cabeceras de un envelope (los datos viajan como cuerpo del mensaje). */
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
   * Reconstruye un envelope a partir de las cabeceras y el cuerpo del transporte.
   *
   * @param headers búsqueda de cabeceras
   * @param names nombres de cabeceras presentes
   * @param body cuerpo del mensaje
   * @return el envelope
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
    return EventEnvelope.builder()
        .id(id)
        .source(source)
        .type(type)
        .time(time)
        .subject(headers.apply(PREFIX + "subject"))
        .dataContentType(contentType == null ? "application/json" : contentType)
        .data(body)
        .extensions(ext)
        .build();
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
