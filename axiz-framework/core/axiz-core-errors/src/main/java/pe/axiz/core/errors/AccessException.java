package pe.axiz.core.errors;

/** Fallo de autenticación o de autorización. */
public class AccessException extends AxizException {

  private static final long serialVersionUID = 1L;

  public AccessException(ErrorDefinition definition, String logMessage) {
    super(definition, logMessage);
  }

  public static AccessException unauthenticated(String logMessage) {
    return new AccessException(CoreErrors.UNAUTHENTICATED, logMessage);
  }

  public static AccessException forbidden(String logMessage) {
    return new AccessException(CoreErrors.FORBIDDEN, logMessage);
  }
}
