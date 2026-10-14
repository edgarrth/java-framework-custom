package pe.axiz.core.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Envelope encryption: data is encrypted locally with a data key that is itself encrypted by the
 * master key (KMS). Data keys are cached and reused for a bounded time and number of messages to
 * limit calls to KMS. Output: {@code len(encryptedKey) [2 bytes] || encryptedKey || iv || ct}.
 */
public final class EnvelopeEncryptor {

  private final DataKeyProvider provider;
  private final Duration maxAge;
  private final long maxUses;
  private final Clock clock;
  private final Map<String, CachedKey> encryptCache = new ConcurrentHashMap<>();
  private final Map<String, byte[]> decryptCache = new ConcurrentHashMap<>();

  public EnvelopeEncryptor(DataKeyProvider provider, Duration maxAge, long maxUses, Clock clock) {
    this.provider = Objects.requireNonNull(provider);
    this.maxAge = Objects.requireNonNull(maxAge);
    this.maxUses = maxUses;
    this.clock = Objects.requireNonNull(clock);
  }

  public byte[] encrypt(byte[] plaintext, String context) {
    CachedKey key = currentKey(context);
    byte[] ct =
        AesGcm.encrypt(
            AesGcm.key(key.plaintext), plaintext, context.getBytes(StandardCharsets.UTF_8));
    byte[] ek = key.encrypted;
    if (ek.length > 0xFFFF) {
      throw new CryptoException("Encrypted data key too large");
    }
    return ByteBuffer.allocate(2 + ek.length + ct.length)
        .putShort((short) ek.length)
        .put(ek)
        .put(ct)
        .array();
  }

  public byte[] decrypt(byte[] envelope, String context) {
    if (envelope.length < 3) {
      throw new CryptoException("Envelope too short");
    }
    ByteBuffer buf = ByteBuffer.wrap(envelope);
    int len = Short.toUnsignedInt(buf.getShort());
    if (len == 0 || envelope.length < 2 + len) {
      throw new CryptoException("Malformed envelope");
    }
    byte[] ek = new byte[len];
    buf.get(ek);
    byte[] ct = new byte[buf.remaining()];
    buf.get(ct);
    String cacheKey = context + "|" + Arrays.hashCode(ek) + "|" + ek.length;
    byte[] dk = decryptCache.computeIfAbsent(cacheKey, k -> provider.decrypt(ek, context));
    if (decryptCache.size() > 1000) {
      decryptCache.clear();
    }
    return AesGcm.decrypt(AesGcm.key(dk), ct, context.getBytes(StandardCharsets.UTF_8));
  }

  private CachedKey currentKey(String context) {
    Instant now = clock.instant();
    return encryptCache.compute(
        context,
        (k, existing) -> {
          if (existing != null
              && existing.uses < maxUses
              && now.isBefore(existing.created.plus(maxAge))) {
            existing.uses++;
            return existing;
          }
          DataKeyProvider.DataKey dk = provider.generate(context);
          CachedKey fresh = new CachedKey(dk.plaintext(), dk.encrypted(), now);
          fresh.uses = 1;
          return fresh;
        });
  }

  private static final class CachedKey {
    private final byte[] plaintext;
    private final byte[] encrypted;
    private final Instant created;
    private long uses;

    private CachedKey(byte[] plaintext, byte[] encrypted, Instant created) {
      this.plaintext = plaintext;
      this.encrypted = encrypted;
      this.created = created;
    }
  }
}
