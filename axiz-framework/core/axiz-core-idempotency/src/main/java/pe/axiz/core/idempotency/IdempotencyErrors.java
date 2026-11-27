package pe.axiz.core.idempotency;

import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorDefinition;

/** Errores de la capacidad de idempotencia de API. */
public final class IdempotencyErrors {

  public static final ErrorDefinition KEY_REQUIRED =
      ErrorDefinition.of("AXZ-IDEM-0400", ErrorCategory.BAD_REQUEST, "Idempotency-Key required");
  public static final ErrorDefinition IN_PROGRESS =
      ErrorDefinition.of(
          "AXZ-IDEM-0409", ErrorCategory.CONFLICT, "Request with this key in progress");
  public static final ErrorDefinition KEY_REUSED =
      ErrorDefinition.of(
          "AXZ-IDEM-0422",
          ErrorCategory.UNPROCESSABLE,
          "Idempotency-Key reused with another request");

  public static final ErrorDefinition OUTCOME_UNKNOWN =
      ErrorDefinition.of(
          "AXZ-IDEM-1409",
          ErrorCategory.CONFLICT,
          "Operation outcome uncertain; consult the operation status");

  public static final ErrorDefinition RESOLVED_WITHOUT_EFFECT =
      ErrorDefinition.of(
          "AXZ-IDEM-2409",
          ErrorCategory.CONFLICT,
          "Operation resolved without effect; repeat the request with the same Idempotency-Key");

  private IdempotencyErrors() {}
}
