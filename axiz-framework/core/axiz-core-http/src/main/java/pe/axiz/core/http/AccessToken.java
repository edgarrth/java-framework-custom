package pe.axiz.core.http;

import java.time.Instant;
import java.util.Objects;
import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;

/**
 * Token de acceso OAuth2.
 *
 * @param value valor del token
 * @param expiresAt instante de expiración
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
