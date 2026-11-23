package pe.axiz.core.errors;

import java.util.List;

/** Definiciones de error propias del framework (prefijo {@code AXZ}). */
public final class CoreErrors {

  public static final ErrorDefinition VALIDATION_FAILED =
      ErrorDefinition.of("AXZ-CORE-0001", ErrorCategory.BAD_REQUEST, "Invalid request");
  public static final ErrorDefinition MISSING_HEADER =
      ErrorDefinition.of("AXZ-CORE-0002", ErrorCategory.BAD_REQUEST, "Missing or invalid header");
  public static final ErrorDefinition MALFORMED_BODY =
      ErrorDefinition.of("AXZ-CORE-0003", ErrorCategory.BAD_REQUEST, "Malformed request body");
  public static final ErrorDefinition NOT_FOUND =
      ErrorDefinition.of("AXZ-CORE-0404", ErrorCategory.NOT_FOUND, "Resource not found");
  public static final ErrorDefinition METHOD_NOT_ALLOWED =
      ErrorDefinition.of("AXZ-CORE-0405", ErrorCategory.METHOD_NOT_ALLOWED, "Method not allowed");
  public static final ErrorDefinition CONFLICT =
      ErrorDefinition.of("AXZ-CORE-0409", ErrorCategory.CONFLICT, "Conflict with current state");

  /** El cambio de negocio hizo commit; repetirlo duplicaría sus efectos. */
  public static final ErrorDefinition POST_COMMIT_PUBLICATION =
      ErrorDefinition.of(
          "AXZ-CORE-0410",
          ErrorCategory.CONFLICT,
          "Operation committed; event delivery requires reconciliation");

  public static final ErrorDefinition PRECONDITION_FAILED =
      ErrorDefinition.of("AXZ-CORE-0412", ErrorCategory.PRECONDITION_FAILED, "Precondition failed");
  public static final ErrorDefinition PAYLOAD_TOO_LARGE =
      ErrorDefinition.of(
          "AXZ-CORE-0413", ErrorCategory.PAYLOAD_TOO_LARGE, "Request body too large");
  public static final ErrorDefinition UNSUPPORTED_MEDIA_TYPE =
      ErrorDefinition.of(
          "AXZ-CORE-0415", ErrorCategory.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type");
  public static final ErrorDefinition UNAUTHENTICATED =
      ErrorDefinition.of("AXZ-SEC-0401", ErrorCategory.UNAUTHORIZED, "Authentication required");
  public static final ErrorDefinition FORBIDDEN =
      ErrorDefinition.of("AXZ-SEC-0403", ErrorCategory.FORBIDDEN, "Access denied");
  public static final ErrorDefinition TOO_MANY_REQUESTS =
      ErrorDefinition.of("AXZ-SEC-0429", ErrorCategory.TOO_MANY_REQUESTS, "Too many requests");
  public static final ErrorDefinition UNEXPECTED =
      ErrorDefinition.of("AXZ-CORE-0500", ErrorCategory.INTERNAL, "Unexpected error");
  public static final ErrorDefinition NOT_IMPLEMENTED =
      ErrorDefinition.of("AXZ-CORE-0501", ErrorCategory.NOT_IMPLEMENTED, "Not implemented");
  public static final ErrorDefinition UNAVAILABLE =
      ErrorDefinition.of(
          "AXZ-CORE-0503", ErrorCategory.SERVICE_UNAVAILABLE, "Service temporarily unavailable");
  public static final ErrorDefinition EXTERNAL_ERROR =
      ErrorDefinition.of("AXZ-EXT-0502", ErrorCategory.BAD_GATEWAY, "Dependency failure");
  public static final ErrorDefinition EXTERNAL_TIMEOUT =
      ErrorDefinition.of("AXZ-EXT-0504", ErrorCategory.GATEWAY_TIMEOUT, "Dependency timeout");
  public static final ErrorDefinition OUTCOME_UNKNOWN =
      ErrorDefinition.of(
          "AXZ-CORE-0504", ErrorCategory.OUTCOME_UNKNOWN, "Operation outcome uncertain");

  private CoreErrors() {}

  /** Todas las definiciones del framework, registradas por defecto en todo catálogo. */
  public static List<ErrorDefinition> all() {
    return List.of(
        VALIDATION_FAILED,
        MISSING_HEADER,
        MALFORMED_BODY,
        NOT_FOUND,
        METHOD_NOT_ALLOWED,
        CONFLICT,
        POST_COMMIT_PUBLICATION,
        PRECONDITION_FAILED,
        PAYLOAD_TOO_LARGE,
        UNSUPPORTED_MEDIA_TYPE,
        UNAUTHENTICATED,
        FORBIDDEN,
        TOO_MANY_REQUESTS,
        UNEXPECTED,
        NOT_IMPLEMENTED,
        UNAVAILABLE,
        EXTERNAL_ERROR,
        EXTERNAL_TIMEOUT,
        OUTCOME_UNKNOWN);
  }

  /** Definición por defecto del framework para una categoría. */
  public static ErrorDefinition forCategory(ErrorCategory category) {
    return switch (category) {
      case BAD_REQUEST -> VALIDATION_FAILED;
      case UNAUTHORIZED -> UNAUTHENTICATED;
      case FORBIDDEN -> FORBIDDEN;
      case NOT_FOUND -> NOT_FOUND;
      case METHOD_NOT_ALLOWED -> METHOD_NOT_ALLOWED;
      case CONFLICT, UNPROCESSABLE -> CONFLICT;
      case PRECONDITION_FAILED -> PRECONDITION_FAILED;
      case PAYLOAD_TOO_LARGE -> PAYLOAD_TOO_LARGE;
      case UNSUPPORTED_MEDIA_TYPE -> UNSUPPORTED_MEDIA_TYPE;
      case TOO_MANY_REQUESTS -> TOO_MANY_REQUESTS;
      case INTERNAL -> UNEXPECTED;
      case NOT_IMPLEMENTED -> NOT_IMPLEMENTED;
      case BAD_GATEWAY -> EXTERNAL_ERROR;
      case SERVICE_UNAVAILABLE -> UNAVAILABLE;
      case GATEWAY_TIMEOUT -> EXTERNAL_TIMEOUT;
      case OUTCOME_UNKNOWN -> OUTCOME_UNKNOWN;
    };
  }
}
