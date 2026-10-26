package pe.axiz.core.lock;

import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorDefinition;

/** Errors of the lock capability. */
public final class LockErrors {

  public static final ErrorDefinition BUSY =
      ErrorDefinition.of("AXZ-LOCK-0409", ErrorCategory.CONFLICT, "Resource busy, retry later");

  /**
   * The lease ended before the work did: another owner may have taken the resource, so the result
   * of the work cannot be trusted and must not be reported as a success.
   */
  public static final ErrorDefinition LOST =
      ErrorDefinition.of(
          "AXZ-LOCK-0503",
          ErrorCategory.SERVICE_UNAVAILABLE,
          "Lock lost before the work finished, retry later");

  private LockErrors() {}
}
