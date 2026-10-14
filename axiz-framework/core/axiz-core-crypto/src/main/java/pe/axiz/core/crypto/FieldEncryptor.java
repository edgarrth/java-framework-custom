package pe.axiz.core.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Encrypts individual values (database fields) with AES-256-GCM. Text form: {@code
 * axz1:<keyId>:<base64url(iv||ciphertext)>}. The field context (for example {@code
 * customer.document}) is bound as AAD, so a value copied to another column fails to decrypt.
 */
public final class FieldEncryptor {

  private static final String PREFIX = "axz1:";

  private final KeyProvider keys;

  public FieldEncryptor(KeyProvider keys) {
    this.keys = Objects.requireNonNull(keys);
  }

  public @Nullable String encrypt(@Nullable String plaintext, String context) {
    if (plaintext == null) {
      return null;
    }
    String keyId = keys.currentKeyId();
    byte[] ct =
        AesGcm.encrypt(
            keys.key(keyId), plaintext.getBytes(StandardCharsets.UTF_8), aad(context, keyId));
    return PREFIX + keyId + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(ct);
  }

  public @Nullable String decrypt(@Nullable String value, String context) {
    if (value == null) {
      return null;
    }
    Parsed p = parse(value);
    byte[] pt = AesGcm.decrypt(keys.key(p.keyId), p.payload, aad(context, p.keyId));
    return new String(pt, StandardCharsets.UTF_8);
  }

  /** Whether the value was encrypted with an old key and should be re-encrypted. */
  public boolean needsRotation(String value) {
    return !parse(value).keyId.equals(keys.currentKeyId());
  }

  public boolean isEncrypted(@Nullable String value) {
    return value != null && value.startsWith(PREFIX) && value.indexOf(':', PREFIX.length()) > 0;
  }

  private static byte[] aad(String context, String keyId) {
    return (context + "|" + keyId).getBytes(StandardCharsets.UTF_8);
  }

  private Parsed parse(String value) {
    if (!isEncrypted(value)) {
      throw new CryptoException("Value is not an axiz encrypted field");
    }
    int sep = value.indexOf(':', PREFIX.length());
    try {
      return new Parsed(
          value.substring(PREFIX.length(), sep),
          Base64.getUrlDecoder().decode(value.substring(sep + 1)));
    } catch (IllegalArgumentException e) {
      throw new CryptoException("Malformed encrypted field", e);
    }
  }

  private record Parsed(String keyId, byte[] payload) {}
}
