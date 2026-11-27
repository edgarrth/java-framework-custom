package pe.axiz.spring.idempotency;

import java.io.Serial;
import java.util.Objects;
import pe.axiz.core.idempotency.StoredResponse;

/**
 * Señal de control de flujo: la solicitud repite una ya completada y debe devolverse su respuesta
 * almacenada. La lanza el advice de idempotencia, que se ejecuta después de la autorización de
 * métodos, y {@link IdempotencyReplayHandler} la convierte en la respuesta. No es un error.
 */
public final class IdempotentReplay extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final transient StoredResponse response;

  public IdempotentReplay(StoredResponse response) {
    super("idempotent replay", null, false, false);
    this.response = Objects.requireNonNull(response);
  }

  public StoredResponse response() {
    return response;
  }
}
