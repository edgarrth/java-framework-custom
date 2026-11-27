package pe.axiz.core.tokenization.vault;

import java.util.UUID;
import pe.axiz.core.tokenization.TokenErrors;

/**
 * Identificadores cerrados y acotados provenientes del contexto autenticado o de la configuración
 * del servidor.
 */
final class VaultIdentifiers {
  private VaultIdentifiers() {}

  static String identifier(String value, int maximum) {
    if (value == null
        || value.length() > maximum
        || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:@/-]*"))
      throw TokenErrors.failure(TokenErrors.INVALID);
    return value;
  }

  static String key(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_-]{1,32}"))
      throw TokenErrors.failure(TokenErrors.INVALID);
    return value;
  }

  static UUID operationId(String value) {
    if (value == null || value.length() != 36) throw TokenErrors.failure(TokenErrors.INVALID);
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equals(value) || id.version() != 4 || id.variant() != 2)
        throw TokenErrors.failure(TokenErrors.INVALID);
      return id;
    } catch (IllegalArgumentException invalid) {
      throw TokenErrors.failure(TokenErrors.INVALID);
    }
  }
}
