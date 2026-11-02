package pe.axiz.spring.messaging;

import java.util.Objects;
import pe.axiz.core.messaging.PayloadCodec;
import pe.axiz.core.messaging.PayloadException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** JSON payloads with the application's Jackson mapper (same modules as the HTTP API). */
public class JacksonPayloadCodec implements PayloadCodec {

  private final JsonMapper mapper;

  public JacksonPayloadCodec(JsonMapper mapper) {
    this.mapper =
        Objects.requireNonNull(mapper)
            .rebuild()
            .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // Nested schema objects use Map/List values; binary floating point would lose amounts.
            .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();
  }

  @Override
  public String contentType() {
    return "application/json";
  }

  @Override
  public byte[] encode(Object payload) {
    try {
      return mapper.writeValueAsBytes(payload);
    } catch (JacksonException e) {
      throw new PayloadException("Cannot encode " + payload.getClass().getName(), e);
    }
  }

  @Override
  public <T> T decode(byte[] data, Class<T> type) {
    try {
      return mapper.readValue(data, type);
    } catch (JacksonException e) {
      throw new PayloadException("Cannot decode " + type.getName(), e);
    }
  }
}
