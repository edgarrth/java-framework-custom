package pe.axiz.core.crypto;

import java.util.Objects;

/** Campo declarado o propósito compartido; se prohíbe el contexto genérico. */
public final class EncryptionContext {
  private EncryptionContext() {}

  public static String require(String value) {
    Objects.requireNonNull(value, "encryption context");
    if (value.length() > 256
        || value.startsWith("axiz.")
        || !value.matches(
            "(?:[A-Za-z][A-Za-z0-9_-]*\\.[A-Za-z][A-Za-z0-9_.-]*|"
                + "purpose:[A-Za-z][A-Za-z0-9_.-]*)(?::[^\\r\\n]{1,128})?"))
      throw new IllegalArgumentException(
          "Encryption context must be entity.field or purpose:name; axiz.field is forbidden");
    StrictUtf8.encode(value, Aad.MAX_PART_BYTES);
    return value;
  }
}
