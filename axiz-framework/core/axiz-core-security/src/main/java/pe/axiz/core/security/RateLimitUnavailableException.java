package pe.axiz.core.security;

/** El almacén no pudo decidir un permiso; distinto de una cuota agotada. */
public final class RateLimitUnavailableException extends RuntimeException {
  public RateLimitUnavailableException(Throwable cause) {
    super("Rate limiter unavailable", cause);
  }
}
