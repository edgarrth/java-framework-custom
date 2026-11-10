package pe.axiz.core.http;

import java.time.Instant;
import java.util.Objects;
import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;

/**
 * OAuth2 access token.
 *
 * @param value token value
 * @param expiresAt expiry instant
 */
public record AccessToken(
    @Sensitive(level = SensitivityLevel.CRITICAL, mask = MaskType.FULL) String value,
    Instant expiresAt) {

  public AccessToken {
    Objects.requireNonNull(value);
    Objects.requireNonNull(expiresAt);
  }

  @Override
  public String toString() {
    return "AccessToken[expiresAt=" + expiresAt + "]";
  }
}
