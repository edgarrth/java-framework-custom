package pe.axiz.core.http;

import java.time.Instant;
import java.util.Objects;

/**
 * OAuth2 access token.
 *
 * @param value token value
 * @param expiresAt expiry instant
 */
public record AccessToken(String value, Instant expiresAt) {

  public AccessToken {
    Objects.requireNonNull(value);
    Objects.requireNonNull(expiresAt);
  }

  @Override
  public String toString() {
    return "AccessToken[expiresAt=" + expiresAt + "]";
  }
}
