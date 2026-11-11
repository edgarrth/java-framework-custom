package pe.axiz.core.crypto;

import java.util.Objects;

/** A declared field or shared purpose; generic context is forbidden. */
public final class EncryptionContext {
  private EncryptionContext() {}

  public static String require(String value) {
    Objects.requireNonNull(value, "encryption context");
    if (value.length() > 256
        || value.equals("axiz.field")
        || !value.matches(
            "(?:[A-Za-z][A-Za-z0-9_-]*\\.[A-Za-z][A-Za-z0-9_.-]*|"
                + "purpose:[A-Za-z][A-Za-z0-9_.-]*)(?::[^\\r\\n]{1,128})?"))
      throw new IllegalArgumentException(
          "Encryption context must be entity.field or purpose:name; axiz.field is forbidden");
    return value;
  }
}
