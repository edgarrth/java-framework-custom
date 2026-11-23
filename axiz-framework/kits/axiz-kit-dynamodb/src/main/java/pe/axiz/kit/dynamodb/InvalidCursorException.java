package pe.axiz.kit.dynamodb;

import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorDefinition;

/**
 * Una firma ausente, una clave desconocida, un payload alterado o un enlace de consulta alterado
 * son un error del cliente.
 */
public final class InvalidCursorException extends BusinessException {
  public static final ErrorDefinition INVALID_CURSOR =
      ErrorDefinition.of("AXZ-DDB-0400", ErrorCategory.BAD_REQUEST, "Invalid pagination cursor");

  public InvalidCursorException() {
    super(INVALID_CURSOR, "Invalid pagination cursor; restart pagination");
  }
}
