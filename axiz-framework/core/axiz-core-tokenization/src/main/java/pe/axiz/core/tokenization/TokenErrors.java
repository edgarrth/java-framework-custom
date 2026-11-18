package pe.axiz.core.tokenization;

import java.time.Duration;
import java.util.List;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorDefinition;
import pe.axiz.core.errors.RateLimitedException;

/** Stable failure catalog; messages never include submitted or stored sensitive values. */
public final class TokenErrors {
  private TokenErrors() {}

  public static final ErrorDefinition INVALID =
      ErrorDefinition.of(
          "AXZ-TOKEN-0400", ErrorCategory.BAD_REQUEST, "Invalid tokenization request");
  public static final ErrorDefinition DENIED =
      ErrorDefinition.of("AXZ-TOKEN-0403", ErrorCategory.FORBIDDEN, "Token operation denied");
  public static final ErrorDefinition NOT_FOUND =
      ErrorDefinition.of("AXZ-TOKEN-0404", ErrorCategory.NOT_FOUND, "Token not found");
  public static final ErrorDefinition LIMITED =
      ErrorDefinition.of(
          "AXZ-TOKEN-0429", ErrorCategory.TOO_MANY_REQUESTS, "Token budget exhausted");
  public static final ErrorDefinition UNSUPPORTED =
      ErrorDefinition.of(
          "AXZ-TOKEN-0501", ErrorCategory.NOT_IMPLEMENTED, "Token operation unsupported");
  public static final ErrorDefinition UNAVAILABLE =
      ErrorDefinition.of(
          "AXZ-TOKEN-0503", ErrorCategory.SERVICE_UNAVAILABLE, "Token service unavailable");
  public static final ErrorDefinition UNCERTAIN =
      ErrorDefinition.of(
          "AXZ-TOKEN-0504", ErrorCategory.OUTCOME_UNKNOWN, "Token operation outcome uncertain");

  public static BusinessException failure(ErrorDefinition definition) {
    return new BusinessException(definition, definition.title());
  }

  public static RateLimitedException limited(Duration retryAfter) {
    return new RateLimitedException(LIMITED, retryAfter);
  }

  /** Closed public error catalog; preserve typed retry delay, never provider messages or causes. */
  public static BusinessException sanitize(Throwable failure) {
    Throwable current = failure;
    for (int depth = 0; current != null && depth < 64; depth++, current = current.getCause()) {
      if (!(current instanceof AxizException axiz)) continue;
      if (current instanceof RateLimitedException limited && limited.code().equals(LIMITED.code()))
        return limited(Duration.ofSeconds(limited.retryAfterSeconds()));
      for (var definition :
          List.of(INVALID, DENIED, NOT_FOUND, LIMITED, UNSUPPORTED, UNAVAILABLE, UNCERTAIN))
        if (definition.code().equals(axiz.code())) return failure(definition);
      return failure(UNAVAILABLE);
    }
    return failure(UNAVAILABLE);
  }
}
