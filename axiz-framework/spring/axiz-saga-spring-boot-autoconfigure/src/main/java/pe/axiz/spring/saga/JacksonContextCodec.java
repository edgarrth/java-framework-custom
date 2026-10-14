package pe.axiz.spring.saga;

import java.util.Objects;
import pe.axiz.core.saga.ContextCodec;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON saga contexts with the service's {@link JsonMapper}: contexts should be records with plain
 * values (ids, amounts, flags), never secrets, since they are persisted with the saga state.
 *
 * @param <C> context type
 */
public final class JacksonContextCodec<C> implements ContextCodec<C> {

  private final JsonMapper mapper;
  private final Class<C> type;

  public JacksonContextCodec(JsonMapper mapper, Class<C> type) {
    this.mapper = Objects.requireNonNull(mapper);
    this.type = Objects.requireNonNull(type);
  }

  @Override
  public String encode(C context) {
    return mapper.writeValueAsString(context);
  }

  @Override
  public C decode(String text) {
    return mapper.readValue(text, type);
  }
}
