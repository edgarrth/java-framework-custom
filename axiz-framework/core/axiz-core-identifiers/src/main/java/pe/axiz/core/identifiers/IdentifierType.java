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

  /**
   * Masks the value for logs and responses; by default reveals at most 25% and four suffix
   * characters.
   */
  default String mask(String normalizedValue) {
    return IdentifierMasks.tail(normalizedValue, 4);
  }
}
