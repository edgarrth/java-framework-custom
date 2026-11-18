package pe.axiz.core.tokenization;

import java.util.Map;

/** Registered, versioned textual codec. Implementations return an owned canonical copy. */
public interface TokenProfile {
  String id();

  int version();

  SensitiveValue canonicalize(SensitiveValue input);

  default boolean cardData() {
    return false;
  }

  /** Only explicitly approved non-sensitive metadata; no generic reflection or toString(). */
  default Map<String, String> metadata(SensitiveValue canonical) {
    return Map.of();
  }
}
