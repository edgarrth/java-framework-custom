package pe.axiz.core.crypto;

import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encrypts individual values (database fields) with AES-256-GCM. Text form: {@code
 * axz1:<keyId>:<base64url(iv||ciphertext)>}. The field context (for example {@code
 * customer.document}) is bound as AAD, so a value copied to another column fails to decrypt.
 */
public final class FieldEncryptor {

  private static final String PREFIX = "axz1:";

  private static final Logger LOG = LoggerFactory.getLogger(FieldEncryptor.class);
  private final KeyProvider keys;
  private final ConcurrentHashMap<String, AtomicLong> uses = new ConcurrentHashMap<>();

  public FieldEncryptor(KeyProvider keys) {
    this.keys = Objects.requireNonNull(keys);
    AesGcm.initialize();
  }

  public @Nullable String encrypt(@Nullable String plaintext, String context) {
    return encrypt(plaintext, context, null);
  }

  public @Nullable String encrypt(
      @Nullable String plaintext, String context, @Nullable String binding) {
    if (plaintext == null) {
      return null;
    }
    String keyId = keys.currentKeyId();
    if (!keyId.matches("[A-Za-z0-9_-]{1,32}")) throw new CryptoException("Invalid key id");
    byte[] clear = StrictUtf8.encode(plaintext, EncryptedValue.MAX_PLAINTEXT_BYTES);
    try {
      var key = keys.key(keyId);
      byte[] contextBytes = aad(context, keyId, binding);
      if (uses.computeIfAbsent(keyId, ignored -> new AtomicLong()).incrementAndGet() == (1L << 31))
        LOG.warn("Field encryption key reached its rotation warning threshold");
      byte[] ct = AesGcm.encrypt(key, clear, contextBytes);
      return PREFIX + keyId + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(ct);
    } finally {
      Arrays.fill(clear, (byte) 0);
    }
  }

  public @Nullable String decrypt(@Nullable String value, String context) {
    return decrypt(value, context, null);
  }

  public @Nullable String decrypt(
      @Nullable String value, String context, @Nullable String binding) {
    if (value == null) {
      return null;
    }
    EncryptedValue parsed = EncryptedValue.parse(value);
    byte[] pt =
        AesGcm.decrypt(
            keys.key(parsed.keyId()), parsed.payload(), aad(context, parsed.keyId(), binding));
    try {
      return StrictUtf8.decode(pt, EncryptedValue.MAX_PLAINTEXT_BYTES);
    } finally {
      Arrays.fill(pt, (byte) 0);
    }
  }

  /** Rebinds authenticated context even when the encryption key is still current. */
  public @Nullable String reencrypt(@Nullable String value, String oldContext, String newContext) {
    return encrypt(decrypt(value, oldContext), newContext);
  }

  /** Whether the value was encrypted with an old key and should be re-encrypted. */
  public boolean needsRotation(String value) {
    return !EncryptedValue.parse(value).keyId().equals(keys.currentKeyId());
  }

  public boolean isEncrypted(@Nullable String value) {
    if (value == null) return false;
    try {
      EncryptedValue.parse(value);
      return true;
    } catch (CryptoException malformed) {
      return false;
    }
  }

  private static byte[] aad(String context, String keyId, @Nullable String binding) {
    EncryptionContext.require(context);
    return binding == null
        ? Aad.of(Aad.FIELD, context, keyId)
        : Aad.of(Aad.FIELD, context, keyId, binding);
  }

  public long encryptions(String keyId) {
    AtomicLong count = uses.get(keyId);
    return count == null ? 0 : count.get();
  }
}
