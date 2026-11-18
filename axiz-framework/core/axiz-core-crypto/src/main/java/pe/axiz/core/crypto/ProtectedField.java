package pe.axiz.core.crypto;

import java.util.Objects;
import pe.axiz.core.sensitive.SelfMasking;

/** Atomic persistence unit for the consumer's own table; contains no plaintext or token. */
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
