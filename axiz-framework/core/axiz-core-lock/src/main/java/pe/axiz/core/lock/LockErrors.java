package pe.axiz.core.lock;

import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorDefinition;

/** Errors of the lock capability. */
public final class LockErrors {

  public static final ErrorDefinition BUSY =
      ErrorDefinition.of("AXZ-LOCK-0409", ErrorCategory.CONFLICT, "Resource busy, retry later");

  private LockErrors() {}
}
