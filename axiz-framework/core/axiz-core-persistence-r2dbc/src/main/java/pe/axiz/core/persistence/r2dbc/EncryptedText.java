package pe.axiz.core.persistence.r2dbc;

import java.util.Objects;

/**
 * A text column stored encrypted (AES-256-GCM with the field encryption keys of the service). The
 * value held here is the plain text; the R2DBC converters of the Spring starter encrypt it on write
 * and decrypt it on read. {@link #toString()} never reveals it.
 *
 * @param value plain text
 */
public record EncryptedText(String value) {

  public EncryptedText {
    Objects.requireNonNull(value, "value");
  }

  public static EncryptedText of(String value) {
    return new EncryptedText(value);
  }

  @Override
  public String toString() {
    return "EncryptedText[****]";
  }
}
