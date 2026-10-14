package pe.axiz.spring.cache;

import java.util.Objects;
import pe.axiz.core.cache.ValueCodec;
import tools.jackson.databind.json.JsonMapper;

/** JSON values with the application mapper. */
public class JacksonValueCodec implements ValueCodec {

  private final JsonMapper mapper;

  public JacksonValueCodec(JsonMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper);
  }

  @Override
  public byte[] encode(Object value) {
    return mapper.writeValueAsBytes(value);
  }

  @Override
  public <T> T decode(byte[] bytes, Class<T> type) {
    return mapper.readValue(bytes, type);
  }

  JsonMapper mapper() {
    return mapper;
  }
}
