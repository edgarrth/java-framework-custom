package pe.axiz.core.errors;

import org.jspecify.annotations.Nullable;

/** Unexpected failure inside the service. */
public class TechnicalException extends AxizException {

  private static final long serialVersionUID = 1L;

  public TechnicalException(String logMessage, @Nullable Throwable cause) {
    super(CoreErrors.UNEXPECTED, logMessage, cause);
  }

  public TechnicalException(
      ErrorDefinition definition, String logMessage, @Nullable Throwable cause) {
    super(definition, logMessage, cause);
  }
}
