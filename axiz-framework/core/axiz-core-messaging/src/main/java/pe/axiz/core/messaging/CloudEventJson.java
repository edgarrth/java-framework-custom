package pe.axiz.core.messaging;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.json.JsonReader;
import pe.axiz.core.json.JsonWriter;

/**
 * CloudEvents JSON format (structured mode), used where transport attributes are limited (SQS, SNS)
 * and for outbox storage. EventEnvelope contains encoded binary bytes: structured serialization
 * uses data_base64 and retains their declared media type, preserving signatures and numeric bytes.
 */
public final class CloudEventJson {

  private static final Set<String> CORE =
      Set.of(
          "specversion",
          "id",
          "source",
          "type",
          "time",
          "subject",
          "datacontenttype",
          "data",
          "data_base64");

  private CloudEventJson() {}

  public static String write(EventEnvelope e) {
    Map<String, @Nullable Object> m = new LinkedHashMap<>();
    m.put("specversion", EventEnvelope.SPEC_VERSION);
    m.put("id", e.id());
    m.put("source", e.source());
    m.put("type", e.type());
    m.put("time", e.time().toString());
    if (e.subject() != null) {
      m.put("subject", e.subject());
    }
    m.put("datacontenttype", e.dataContentType());
    m.putAll(e.extensions());
    m.put("data_base64", Base64.getEncoder().encodeToString(e.data()));
    return JsonWriter.write(m);
  }

  public static EventEnvelope read(String json) {
    Map<String, @Nullable Object> m;
    try {
      m = JsonReader.parseObject(json);
    } catch (IllegalArgumentException ex) {
      throw new PayloadException("Message is not a CloudEvents JSON document", ex);
    }
    String contentType = string(m, "datacontenttype", "application/json");
    if (m.containsKey("data") && m.containsKey("data_base64"))
      throw new PayloadException(
          "CloudEvents data and data_base64 are mutually exclusive",
          new IllegalArgumentException("Ambiguous payload"));
    byte[] data;
    if (m.containsKey("data_base64")) {
      data = Base64.getDecoder().decode(string(m, "data_base64", ""));
    } else {
      Object d = m.get("data");
      data =
          d == null
              ? new byte[0]
              : (d instanceof String s && !isJson(contentType) ? s : JsonWriter.write(d))
                  .getBytes(StandardCharsets.UTF_8);
    }
    Map<String, String> ext = new LinkedHashMap<>();
    m.forEach(
        (k, v) -> {
          if (!CORE.contains(k) && v != null) {
            ext.put(
                k.toLowerCase(Locale.ROOT),
                v instanceof BigDecimal bd ? bd.toPlainString() : v.toString());
          }
        });
    Instant time;
    try {
      time = Instant.parse(string(m, "time", Instant.EPOCH.toString()));
    } catch (DateTimeParseException ex) {
      throw new PayloadException("Invalid time", ex);
    }
    Object subject = m.get("subject");
    return EventEnvelope.builder()
        .id(required(m, "id"))
        .source(required(m, "source"))
        .type(required(m, "type"))
        .time(time)
        .subject(subject == null ? null : subject.toString())
        .dataContentType(contentType)
        .data(data)
        .extensions(ext)
        .build();
  }

  private static boolean isJson(String contentType) {
    String ct = contentType.toLowerCase(Locale.ROOT);
    return ct.startsWith("application/json") || ct.contains("+json");
  }

  private static String required(Map<String, @Nullable Object> m, String key) {
    Object v = m.get(key);
    if (v == null || v.toString().isBlank()) {
      throw new PayloadException(
          "Missing CloudEvents attribute " + key, new IllegalArgumentException(key));
    }
    return v.toString();
  }

  private static String string(Map<String, @Nullable Object> m, String key, String fallback) {
    Object v = m.get(key);
    return v == null ? fallback : v.toString();
  }
}
