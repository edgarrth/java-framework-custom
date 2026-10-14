package pe.axiz.core.persistence.dynamodb;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.json.JsonReader;
import pe.axiz.core.commons.json.JsonWriter;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Opaque pagination cursor: the DynamoDB {@code LastEvaluatedKey} (string and number keys) encoded
 * as base64url JSON.
 */
public final class CursorCodec {

  private CursorCodec() {}

  public static @Nullable String encode(@Nullable Map<String, AttributeValue> lastEvaluatedKey) {
    if (lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()) {
      return null;
    }
    Map<String, Object> json = new LinkedHashMap<>();
    lastEvaluatedKey.forEach(
        (k, v) ->
            json.put(k, v.n() != null ? Map.of("N", v.n()) : Map.of("S", String.valueOf(v.s()))));
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(JsonWriter.write(json).getBytes(StandardCharsets.UTF_8));
  }

  public static @Nullable Map<String, AttributeValue> decode(@Nullable String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    try {
      Map<String, @Nullable Object> json =
          JsonReader.parseObject(
              new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
      Map<String, AttributeValue> key = new LinkedHashMap<>();
      json.forEach(
          (k, v) -> {
            if (v instanceof Map<?, ?> m && m.get("N") != null) {
              key.put(k, AttributeValue.builder().n(String.valueOf(m.get("N"))).build());
            } else if (v instanceof Map<?, ?> m && m.get("S") != null) {
              key.put(k, AttributeValue.builder().s(String.valueOf(m.get("S"))).build());
            } else {
              throw new IllegalArgumentException("Invalid cursor");
            }
          });
      return key;
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid pagination cursor", e);
    }
  }
}
