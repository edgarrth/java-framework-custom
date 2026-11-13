package pe.axiz.core.tenancy;

import pe.axiz.core.errors.AccessException;
import pe.axiz.core.errors.CoreErrors;

/** A present tenant claim is invalid; a trusted header must not replace it. */
public final class InvalidTenantException extends AccessException {
  private static final long serialVersionUID = 1L;

  public InvalidTenantException() {
    super(CoreErrors.UNAUTHENTICATED, "Invalid tenant claim");
  }
}
