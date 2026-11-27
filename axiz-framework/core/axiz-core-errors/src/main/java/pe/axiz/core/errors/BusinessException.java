package pe.axiz.core.errors;

import java.util.List;
import java.util.Map;

/** Una regla de negocio rechazó la operación. */
public class BusinessException extends AxizException {

  private static final long serialVersionUID = 1L;

  public BusinessException(ErrorDefinition definition, String logMessage) {
    super(definition, logMessage);
  }

  public BusinessException(
      ErrorDefinition definition, String logMessage, Map<String, ?> parameters) {
    super(definition, logMessage, parameters, List.of(), null, null);
  }
}
