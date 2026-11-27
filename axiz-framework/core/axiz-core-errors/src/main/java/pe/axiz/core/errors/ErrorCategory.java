package pe.axiz.core.errors;

/**
 * Categorías de error con su estado HTTP. No se produce ningún estado HTTP sin una categoría.
 *
 * <p>{@link #retryable()} indica a los clientes y a las políticas de resiliencia si repetir la
 * misma petición puede tener éxito.
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
  /**
   * La mutación puede haber hecho commit; se reconcilia antes de cualquier reintento deliberado.
   */
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
   * Categoría por defecto para un estado HTTP recibido de una dependencia. Un 504 sin más no
   * permite distinguir un timeout de una mutación incierta; los clientes de protocolo deben
   * preservar el código de error explícito. Los 4xx desconocidos se convierten en {@link
   * #UNPROCESSABLE}; los 5xx desconocidos, en {@link #BAD_GATEWAY}; cualquier otro, en {@link
   * #INTERNAL}.
   *
   * @param status código de estado HTTP
   * @return categoría correspondiente
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
   * Categoría usada cuando una dependencia responde con el estado indicado: los errores de servidor
   * propios se informan como errores de gateway porque la falla no está en este servicio.
   *
   * @param status estado HTTP devuelto por la dependencia
   * @return la categoría que informa este servicio
   */
  public static ErrorCategory forExternalStatus(int status) {
    return switch (status) {
      case 401, 403 -> BAD_GATEWAY; // las credenciales propias ante la dependencia son incorrectas
      case 404 -> NOT_FOUND;
      case 409 -> CONFLICT;
      case 408, 504 -> GATEWAY_TIMEOUT;
      case 429, 503 -> SERVICE_UNAVAILABLE;
      default -> status >= 500 ? BAD_GATEWAY : UNPROCESSABLE;
    };
  }
}
