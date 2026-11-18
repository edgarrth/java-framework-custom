package pe.axiz.spring.security;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.AuthenticationException;

/** Unavailable verification infrastructure, without provider text or credentials. */
public final class IssuerUnavailableException extends AuthenticationException {
  private static final long serialVersionUID = 1L;
  private final long retryAfterSeconds;

  public IssuerUnavailableException(long retryAfterSeconds) {
    super("Token issuer verification is temporarily unavailable");
    this.retryAfterSeconds = Math.max(1, Math.min(60, retryAfterSeconds));
  }

  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }

  /** Looks through framework wrappers, with a finite bound even for cyclic causes. */
  static @Nullable IssuerUnavailableException find(Throwable failure) {
    Throwable current = failure;
    for (int i = 0; i < 16 && current != null; i++, current = current.getCause()) {
      if (current instanceof IssuerUnavailableException unavailable) return unavailable;
    }
    return null;
  }
}
