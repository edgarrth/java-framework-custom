package pe.axiz.core.security;

/** The store could not decide a permit; distinct from an exhausted quota. */
public final class RateLimitUnavailableException extends RuntimeException {
  public RateLimitUnavailableException(Throwable cause) {
    super("Rate limiter unavailable", cause);
  }
}
