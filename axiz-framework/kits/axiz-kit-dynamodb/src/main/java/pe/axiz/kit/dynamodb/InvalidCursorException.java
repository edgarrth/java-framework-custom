package pe.axiz.kit.dynamodb;

import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorDefinition;

/** A missing signature, unknown key, altered payload or query binding is a client error. */
public final class InvalidCursorException extends BusinessException {
  public static final ErrorDefinition INVALID_CURSOR =
      ErrorDefinition.of("AXZ-DDB-0400", ErrorCategory.BAD_REQUEST, "Invalid pagination cursor");

  public InvalidCursorException() {
    super(INVALID_CURSOR, "Invalid pagination cursor; restart pagination");
  }
}
