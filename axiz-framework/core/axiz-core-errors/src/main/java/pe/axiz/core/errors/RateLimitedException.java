package pe.axiz.core.errors;

import java.time.Duration;
import java.util.Objects;

/**
 * Decisión 429 confirmada con un retraso acotado y redondeado hacia arriba, apto para Retry-After.
 */
public final class RateLimitedException extends BusinessException {
  private static final long serialVersionUID = 1L;
  private final long retryAfterSeconds;

  public RateLimitedException(ErrorDefinition definition, Duration retryAfter) {
    super(definition, definition.title());
    Objects.requireNonNull(retryAfter);
    if (definition.category() != ErrorCategory.TOO_MANY_REQUESTS)
      throw new IllegalArgumentException("Rate-limit metadata requires a 429 definition");
    Duration bounded =
        retryAfter.compareTo(Duration.ofDays(1)) > 0
            ? Duration.ofDays(1)
            : retryAfter.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : retryAfter;
    retryAfterSeconds = bounded.getSeconds() + (bounded.getNano() == 0 ? 0 : 1);
  }

  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
