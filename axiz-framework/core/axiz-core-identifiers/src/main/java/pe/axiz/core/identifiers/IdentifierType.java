package pe.axiz.core.identifiers;

import java.util.Locale;

/**
 * Service provider interface for any kind of identifier (document, account, card, contact...).
 * Implementations must be stateless and thread-safe.
 */
public interface IdentifierType {

  /** Unique lowercase name used in configuration and annotations, e.g. {@code "dni"}. */
  String name();

  /** Validates an already normalized value. */
  boolean isValid(String normalizedValue);

  /** Normalizes user input before validation: strips spaces and dashes and uppercases. */
  default String normalize(String raw) {
    return raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
  }

  /** Masks the value for logs and responses; by default keeps the last four characters. */
  default String mask(String normalizedValue) {
    int keep = Math.min(4, normalizedValue.length() / 2);
    return "*".repeat(normalizedValue.length() - keep)
        + normalizedValue.substring(normalizedValue.length() - keep);
  }
}
