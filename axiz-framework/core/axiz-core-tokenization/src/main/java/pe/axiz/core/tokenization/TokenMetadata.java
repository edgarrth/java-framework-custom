package pe.axiz.core.tokenization;

import java.util.Map;

/**
 * Acota las instantáneas. El perfil del servidor además aplica su lista explícita de claves
 * permitidas.
 */
public final class TokenMetadata {
  private TokenMetadata() {}

  public static Map<String, String> snapshot(Map<String, String> metadata) {
    if (metadata.size() > 16) throw TokenErrors.failure(TokenErrors.INVALID);
    metadata.forEach(
        (key, value) -> {
          if (key == null
              || !key.matches("[a-zA-Z][a-zA-Z0-9_-]{0,31}")
              || value == null
              || value.length() > 256
              || value.codePoints().anyMatch(Character::isISOControl))
            throw TokenErrors.failure(TokenErrors.INVALID);
        });
    return Map.copyOf(metadata);
  }
}
