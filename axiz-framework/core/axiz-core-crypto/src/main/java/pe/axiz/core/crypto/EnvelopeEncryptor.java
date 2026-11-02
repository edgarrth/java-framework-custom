package pe.axiz.core.crypto;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Envelope encryption: data is encrypted locally with a data key that is itself encrypted by the
 * master key (KMS). Output: {@code len(encryptedKey) [2 bytes] || encryptedKey || iv || ct}.
 *
 * <p>Data keys are cached to limit calls to KMS, and every cached key has bounds:
 *
 * <ul>
 *   <li>a key for encrypting is reused for at most {@code maxAge} and {@code maxUses} messages;
 *   <li>a decrypted key is reused for at most {@code maxAge}, and only for exactly the same
 *       encrypted key bytes and context: two different encrypted keys never share an entry, so an
 *       envelope is always opened with the key its own header names;
 *   <li>each cache keeps at most {@value #MAX_CACHED_KEYS} keys, the least recently used leave
 *       first.
 * </ul>
 */
public final class EnvelopeEncryptor {

  /** Keys kept by each cache (encryption keys by context, decrypted keys by encrypted key). */
  public static final int MAX_CACHED_KEYS = 1000;

  private final DataKeyProvider provider;
  private final Duration maxAge;
  private final long maxUses;
  private final Clock clock;
  private final Map<String, CachedKey> encryptCache = boundedLru();
  private final Map<String, CachedKey> decryptCache = boundedLru();

  public EnvelopeEncryptor(DataKeyProvider provider, Duration maxAge, long maxUses, Clock clock) {
    this.provider = Objects.requireNonNull(provider);
    this.maxAge = Objects.requireNonNull(maxAge);
    EnvelopePayload.validatePolicy(maxAge, maxUses);
    this.maxUses = maxUses;
    this.clock = Objects.requireNonNull(clock);
  }

  private static Map<String, CachedKey> boundedLru() {
    return Collections.synchronizedMap(
        new LinkedHashMap<>(16, 0.75f, true) {
          private static final long serialVersionUID = 1L;

          @Override
          protected boolean removeEldestEntry(Map.Entry<String, CachedKey> eldest) {
            return size() > MAX_CACHED_KEYS;
          }
        });
  }

  public byte[] encrypt(byte[] plaintext, String context) {
    CachedKey key = currentKey(context);
    return EnvelopePayload.seal(plaintext, key.plaintext, key.encrypted, context);
  }

  public byte[] decrypt(byte[] envelope, String context) {
    EnvelopePayload payload = EnvelopePayload.parse(envelope);
    return payload.open(dataKey(payload.encryptedKey(), context), context);
  }

  /** The decrypted data key for exactly these encrypted key bytes and context. */
  private byte[] dataKey(byte[] encryptedKey, String context) {
    String id = cacheId(encryptedKey, context);
    Instant now = clock.instant();
    CachedKey cached = decryptCache.get(id);
    if (cached != null && now.isBefore(cached.created.plus(maxAge))) {
      return cached.plaintext;
    }
    byte[] plaintext = provider.decrypt(encryptedKey, context);
    decryptCache.put(id, new CachedKey(plaintext, encryptedKey, now));
    return plaintext;
  }

  /**
   * Identity of a cached decrypted key: the context and every byte of the encrypted key (Base64),
   * so it is unique; a hash would let two encrypted keys share an entry.
   */
  static String cacheId(byte[] encryptedKey, String context) {
    return context.length()
        + ":"
        + context
        + ":"
        + Base64.getEncoder().encodeToString(encryptedKey);
  }

  private CachedKey currentKey(String context) {
    Instant now = clock.instant();
    synchronized (encryptCache) {
      CachedKey existing = encryptCache.get(context);
      if (existing != null
          && existing.uses < maxUses
          && now.isBefore(existing.created.plus(maxAge))) {
        existing.uses++;
        return existing;
      }
      DataKeyProvider.DataKey dk = provider.generate(context);
      CachedKey fresh = new CachedKey(dk.plaintext(), dk.encrypted(), now);
      fresh.uses = 1;
      encryptCache.put(context, fresh);
      return fresh;
    }
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
