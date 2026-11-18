package pe.axiz.core.crypto;

import java.util.Base64;
import java.util.Objects;
import pe.axiz.core.sensitive.SelfMasking;

/**
 * Validated storage representation. Parsing does not authenticate it; decrypt with trusted scope.
 */
public final class EncryptedValue implements SelfMasking {
  static final int MAX_PLAINTEXT_BYTES = 1_000_000;
  private final String envelope;
  private final String keyId;
  private final byte[] payload;

  private EncryptedValue(String envelope, String keyId, byte[] payload) {
    this.envelope = envelope;
    this.keyId = keyId;
    this.payload = payload;
  }

  public static EncryptedValue parse(String envelope) {
    if (envelope == null || envelope.length() > 1_400_000)
      throw new CryptoException("Invalid field envelope");
    String[] parts = envelope.split(":", 4);
    if (parts.length != 3
        || !parts[0].equals("axz1")
        || !parts[1].matches("[A-Za-z0-9_-]{1,32}")
        || !parts[2].matches("[A-Za-z0-9_-]+")) throw new CryptoException("Invalid field envelope");
    try {
      byte[] bytes = Base64.getUrlDecoder().decode(parts[2]);
      if (bytes.length < 28
          || bytes.length > MAX_PLAINTEXT_BYTES + 28
          || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(parts[2]))
        throw new CryptoException("Invalid field envelope");
      return new EncryptedValue(envelope, parts[1], bytes);
    } catch (IllegalArgumentException e) {
      throw new CryptoException("Invalid field envelope");
    }
  }

  public String keyId() {
    return keyId;
  }

  byte[] payload() {
    return payload.clone();
  }

  /** Explicit persistence boundary; never feed this representation to a plaintext converter. */
  public String encoded() {
    return envelope;
  }

  @Override
  public String masked() {
    return "****";
  }

  @Override
  public String toString() {
    return "EncryptedValue[****]";
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof EncryptedValue value && envelope.equals(value.envelope);
  }

  @Override
  public int hashCode() {
    return Objects.hash(envelope);
  }
}
