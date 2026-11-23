package pe.axiz.core.tokenization;

import java.util.Map;

/**
 * Codec textual registrado y versionado. Las implementaciones devuelven una copia canónica propia.
 */
public interface TokenProfile {
  String id();

  int version();

  SensitiveValue canonicalize(SensitiveValue input);

  default boolean cardData() {
    return false;
  }

  /**
   * Solo metadatos no sensibles aprobados de forma explícita; sin reflexión genérica ni toString().
   */
  default Map<String, String> metadata(SensitiveValue canonical) {
    return Map.of();
  }
}
