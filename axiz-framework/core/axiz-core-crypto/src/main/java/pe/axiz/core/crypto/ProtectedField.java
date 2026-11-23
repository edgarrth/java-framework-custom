package pe.axiz.core.crypto;

import java.util.Objects;
import pe.axiz.core.sensitive.SelfMasking;

/**
 * Unidad de persistencia atómica para la tabla propia del consumidor; no contiene texto plano ni
 * token.
 */
public record ProtectedField(EncryptedValue ciphertext, KeyedHash index) implements SelfMasking {
  public ProtectedField {
    Objects.requireNonNull(ciphertext);
    Objects.requireNonNull(index);
  }

  @Override
  public String masked() {
    return "****";
  }

  @Override
  public String toString() {
    return "ProtectedField[****]";
  }
}
