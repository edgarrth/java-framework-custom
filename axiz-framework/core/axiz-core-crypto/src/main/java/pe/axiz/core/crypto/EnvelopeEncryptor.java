package pe.axiz.core.crypto;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.crypto.DataKeyProvider.DataKey;

/**
 * Envelopes AES-256-GCM con delimitación autenticada versionada y cargas de claves acotadas y
 * coalescidas.
 */
public final class EnvelopeEncryptor implements AutoCloseable {
  public static final int MAX_CACHED_KEYS = 1000;
  private final DataKeyProvider provider;
  private final SyncDataKeyCache encryptKeys;
  private final SyncDataKeyCache decryptKeys;

  public EnvelopeEncryptor(DataKeyProvider provider, Duration maxAge, long maxUses, Clock clock) {
    this.provider = Objects.requireNonNull(provider);
    EnvelopePayload.validatePolicy(Objects.requireNonNull(maxAge), maxUses);
    Objects.requireNonNull(clock);
    encryptKeys = new SyncDataKeyCache(maxAge, maxUses, clock);
    decryptKeys = new SyncDataKeyCache(maxAge, Long.MAX_VALUE, clock);
  }

  public byte[] encrypt(byte[] plaintext, String context) {
    return encrypt(plaintext, context, null);
  }

  public byte[] encrypt(byte[] plaintext, String context, @Nullable String binding) {
    EnvelopePayload.aad(context, binding);
    try (DataKey key = encryptKeys.get(context, () -> provider.generate(context))) {
      return EnvelopePayload.seal(plaintext, key.plaintext(), key.encrypted(), context, binding);
    }
  }

  public byte[] decrypt(byte[] envelope, String context) {
    return decrypt(envelope, context, null);
  }

  public byte[] decrypt(byte[] envelope, String context, @Nullable String binding) {
    EnvelopePayload.aad(context, binding);
    EnvelopePayload payload = EnvelopePayload.parse(envelope);
    byte[] encrypted = payload.encryptedKey();
    try (DataKey key =
        decryptKeys.get(
            cacheId(encrypted, context),
            () -> {
              byte[] raw = provider.decrypt(encrypted, context);
              try {
                return new DataKey(raw, encrypted);
              } finally {
                Arrays.fill(raw, (byte) 0);
              }
            })) {
      return payload.open(key.plaintext(), context, binding);
    }
  }

  static String cacheId(byte[] encryptedKey, String context) {
    return context.length()
        + ":"
        + context
        + ":"
        + Base64.getEncoder().encodeToString(encryptedKey);
  }

  @Override
  public void close() {
    encryptKeys.close();
    decryptKeys.close();
  }
}
