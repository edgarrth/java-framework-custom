package pe.axiz.spring.idempotency;

import java.io.Serial;
import java.util.Objects;
import pe.axiz.core.idempotency.StoredResponse;

/**
 * Control-flow signal: the request repeats a completed one and its stored response must be
 * returned. It is raised by the idempotency advice, which runs after method authorization, and
 * turned into the response by {@link IdempotencyReplayHandler}. It is not an error.
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
