package pe.axiz.core.errors;

/**
 * Error categories with their HTTP status. No HTTP status is produced without a category.
 *
 * <p>{@link #retryable()} tells clients and resilience policies whether repeating the same request
 * can succeed.
 */
public enum ErrorCategory {
  BAD_REQUEST(400, ErrorType.VALIDATION, false),
  UNAUTHORIZED(401, ErrorType.SECURITY, false),
  FORBIDDEN(403, ErrorType.SECURITY, false),
  NOT_FOUND(404, ErrorType.BUSINESS, false),
  METHOD_NOT_ALLOWED(405, ErrorType.VALIDATION, false),
  CONFLICT(409, ErrorType.BUSINESS, false),
  PRECONDITION_FAILED(412, ErrorType.BUSINESS, false),
  PAYLOAD_TOO_LARGE(413, ErrorType.VALIDATION, false),
  UNSUPPORTED_MEDIA_TYPE(415, ErrorType.VALIDATION, false),
  UNPROCESSABLE(422, ErrorType.BUSINESS, false),
  TOO_MANY_REQUESTS(429, ErrorType.SECURITY, true),
  INTERNAL(500, ErrorType.TECHNICAL, false),
  NOT_IMPLEMENTED(501, ErrorType.TECHNICAL, false),
  BAD_GATEWAY(502, ErrorType.EXTERNAL, true),
  SERVICE_UNAVAILABLE(503, ErrorType.TECHNICAL, true),
  GATEWAY_TIMEOUT(504, ErrorType.EXTERNAL, true),
  /** The mutation may have committed; reconcile before any deliberate retry. */
  OUTCOME_UNKNOWN(504, ErrorType.TECHNICAL, false);

  private final int httpStatus;
  private final ErrorType defaultType;
  private final boolean retryable;

  ErrorCategory(int httpStatus, ErrorType defaultType, boolean retryable) {
    this.httpStatus = httpStatus;
    this.defaultType = defaultType;
    this.retryable = retryable;
  }

  public int httpStatus() {
    return httpStatus;
  }

  public ErrorType defaultType() {
    return defaultType;
  }

  public boolean retryable() {
    return retryable;
  }

  /**
   * Default category for an HTTP status received from a dependency. A bare 504 cannot distinguish a
   * timeout from an uncertain mutation; protocol clients must preserve the explicit error code.
   * Unknown 4xx become {@link #UNPROCESSABLE}; unknown 5xx become {@link #BAD_GATEWAY}; anything
   * else {@link #INTERNAL}.
   *
   * @param status HTTP status code
   * @return matching category
   */
  public static ErrorCategory fromHttpStatus(int status) {
    for (ErrorCategory c : values()) {
      if (c.httpStatus == status) {
        return c;
      }
    }
    if (status == 408) {
      return GATEWAY_TIMEOUT;
    }
    if (status >= 400 && status < 500) {
      return UNPROCESSABLE;
    }
    if (status >= 500 && status < 600) {
      return BAD_GATEWAY;
    }
    return INTERNAL;
  }

  /**
   * Category used when a dependency answers with the given status: our own server errors are
   * reported as gateway errors because the fault is not in this service.
   *
   * @param status HTTP status returned by the dependency
   * @return the category this service reports
   */
  public static ErrorCategory forExternalStatus(int status) {
    return switch (status) {
      case 401, 403 -> BAD_GATEWAY; // our credentials towards the dependency are wrong
      case 404 -> NOT_FOUND;
      case 409 -> CONFLICT;
      case 408, 504 -> GATEWAY_TIMEOUT;
      case 429, 503 -> SERVICE_UNAVAILABLE;
      default -> status >= 500 ? BAD_GATEWAY : UNPROCESSABLE;
    };
  }
}
