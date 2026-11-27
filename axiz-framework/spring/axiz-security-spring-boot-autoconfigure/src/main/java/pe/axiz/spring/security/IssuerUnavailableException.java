package pe.axiz.spring.security;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.AuthenticationException;

/** Infraestructura de verificación no disponible, sin texto del proveedor ni credenciales. */
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

  /** Recorre los wrappers del framework, con un límite finito incluso para causas cíclicas. */
  static @Nullable IssuerUnavailableException find(Throwable failure) {
    Throwable current = failure;
    for (int i = 0; i < 16 && current != null; i++, current = current.getCause()) {
      if (current instanceof IssuerUnavailableException unavailable) return unavailable;
    }
    return null;
  }
}
