package pe.axiz.core.idempotency;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.json.JsonReader;
import pe.axiz.core.json.JsonWriter;

/** Serializes records for key-value stores (Redis, DynamoDB string attribute). */
public final class StoredResponseCodec {

  private StoredResponseCodec() {}

  public static String write(IdempotencyRecord r) {
    Map<String, @Nullable Object> m = new LinkedHashMap<>();
    m.put("fingerprint", r.fingerprint());
    m.put("status", r.status().name());
    if (r.owner() != null) {
      m.put("owner", r.owner());
    }
    m.put("expiresAt", r.retainUntil().toString());
    m.put("leaseUntil", r.leaseUntil().toString());
    m.put("resolutionVersion", r.resolutionVersion());
    if (r.failure() != null) m.put("failure", r.failure());
    StoredResponse resp = r.response();
    if (resp != null) {
      m.put("httpStatus", resp.status());
      m.put("headers", resp.headers());
      m.put("body", Base64.getEncoder().encodeToString(resp.body()));
    }
    return JsonWriter.write(m);
  }

  @SuppressWarnings("unchecked")
  public static IdempotencyRecord read(String key, String json) {
    Map<String, @Nullable Object> m = JsonReader.parseObject(json);
    StoredResponse response = null;
    if (m.get("httpStatus") instanceof BigDecimal status) {
      Map<String, String> headers = new LinkedHashMap<>();
      if (m.get("headers") instanceof Map<?, ?> h) {
        h.forEach((k, v) -> headers.put(String.valueOf(k), String.valueOf(v)));
      }
      Object body = m.get("body");
      response =
          new StoredResponse(
              status.intValue(),
              headers,
              body == null ? new byte[0] : Base64.getDecoder().decode(body.toString()));
    }
    return IdempotencyRecord.builder(
            key,
            String.valueOf(m.get("fingerprint")),
            Instant.parse(String.valueOf(m.getOrDefault("leaseUntil", m.get("expiresAt")))),
            Instant.parse(String.valueOf(m.get("expiresAt"))))
        .status(IdempotencyRecord.Status.valueOf(String.valueOf(m.get("status"))))
        .owner(m.get("owner") == null ? null : String.valueOf(m.get("owner")))
        .response(response)
        .resolutionVersion(
            m.get("resolutionVersion") instanceof BigDecimal version ? version.longValueExact() : 0)
        .failure(m.get("failure") == null ? null : String.valueOf(m.get("failure")))
        .build();
  }

  static String utf8(byte[] b) {
    return new String(b, StandardCharsets.UTF_8);
  }
}
