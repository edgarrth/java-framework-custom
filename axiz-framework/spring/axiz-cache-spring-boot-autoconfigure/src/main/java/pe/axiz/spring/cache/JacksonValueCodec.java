package pe.axiz.spring.cache;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import pe.axiz.core.cache.ValueCodec;
import tools.jackson.databind.json.JsonMapper;

/** Valores JSON con el mapper de la aplicación. */
public final class JacksonValueCodec implements ValueCodec {

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "JsonMapper is an injected collaborator whose identity and lifecycle must be shared, "
              + "not copied.")
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
