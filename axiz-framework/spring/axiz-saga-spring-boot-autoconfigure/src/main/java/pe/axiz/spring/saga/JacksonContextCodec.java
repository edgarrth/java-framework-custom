package pe.axiz.spring.saga;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import pe.axiz.core.saga.ContextCodec;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contextos de saga en JSON con el {@link JsonMapper} del servicio: los contextos deberían ser
 * records con valores simples (ids, montos, flags), nunca secretos, ya que se persisten con el
 * estado de la saga.
 *
 * @param <C> tipo del contexto
 */
public final class JacksonContextCodec<C> implements ContextCodec<C> {

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "JsonMapper is an injected collaborator whose identity and lifecycle must be shared, "
              + "not copied.")
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
