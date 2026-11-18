package pe.axiz.core.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The data key caches: exact identity, age and size bounds. */
class EnvelopeKeyCacheTest {

  /** A clock the test moves by hand. */
  static final class MovingClock extends Clock {
    Instant now = Instant.parse("2026-10-24T10:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  /** A KMS stand-in: each encrypted key has its own data key; counts the calls. */
  static final class FakeKms implements DataKeyProvider {
    final Map<String, byte[]> keys = new HashMap<>();
    final AtomicInteger generated = new AtomicInteger();
    final AtomicInteger decrypted = new AtomicInteger();

    void register(byte[] encrypted, byte[] plaintext) {
      keys.put(Arrays.toString(encrypted), plaintext);
    }

    @Override
    public DataKey generate(String context) {
      int n = generated.incrementAndGet();
      byte[] encrypted = ("ek-" + context + "-" + n).getBytes(StandardCharsets.UTF_8);
      byte[] plaintext = AesGcm.newKey().getEncoded();
      register(encrypted, plaintext);
      return new DataKey(plaintext, encrypted);
    }

    @Override
    public byte[] decrypt(byte[] encryptedKey, String context) {
      decrypted.incrementAndGet();
      byte[] key = keys.get(Arrays.toString(encryptedKey));
      if (key == null) {
        throw new CryptoException("Unknown wrapped key");
      }
      return key.clone();
    }
  }

  private static byte[] envelope(byte[] wrapped, byte[] key, String value) {
    return EnvelopePayload.seal(
        value.getBytes(StandardCharsets.UTF_8), key.clone(), wrapped, "account");
  }

  @Test
  void encryptedKeysWithTheSameHashNeverShareAnEntry() {
    byte[] wrappedA = {0, 31};
    byte[] wrappedB = {1, 0};
    assertEquals(Arrays.hashCode(wrappedA), Arrays.hashCode(wrappedB), "the fixture collides");
    FakeKms kms = new FakeKms();
    byte[] keyA = AesGcm.newKey().getEncoded();
    byte[] keyB = AesGcm.newKey().getEncoded();
    kms.register(wrappedA, keyA);
    kms.register(wrappedB, keyB);
    EnvelopeEncryptor env =
        new EnvelopeEncryptor(kms, Duration.ofMinutes(1), 10, Clock.systemUTC());
    byte[] a = envelope(wrappedA, keyA, "first");
    byte[] b = envelope(wrappedB, keyB, "second");

    assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), env.decrypt(a, "account"));
    assertArrayEquals("second".getBytes(StandardCharsets.UTF_8), env.decrypt(b, "account"));
    assertEquals(2, kms.decrypted.get(), "each encrypted key is asked for once");
    assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), env.decrypt(a, "account"));
    assertEquals(2, kms.decrypted.get(), "and then served from the cache");

    byte[] substituted = a.clone();
    substituted[3] = wrappedB[0];
    substituted[4] = wrappedB[1];
    assertThrows(
        CryptoException.class,
        () -> env.decrypt(substituted, "account"),
        "a header that names another key opens nothing, with a warm cache too");
    assertNotEquals(
        EnvelopeEncryptor.cacheId(wrappedA, "account"),
        EnvelopeEncryptor.cacheId(wrappedB, "account"));
    assertNotEquals(
        EnvelopeEncryptor.cacheId(wrappedA, "a:b"),
        EnvelopeEncryptor.cacheId(wrappedA, "a"),
        "the context is part of the identity");
  }

  @Test
  void decryptedKeysExpireWithTheirAge() {
    MovingClock clock = new MovingClock();
    FakeKms kms = new FakeKms();
    EnvelopeEncryptor writer = new EnvelopeEncryptor(kms, Duration.ofMinutes(5), 100, clock);
    EnvelopeEncryptor reader = new EnvelopeEncryptor(kms, Duration.ofMinutes(5), 100, clock);
    byte[] e = writer.encrypt("x".getBytes(StandardCharsets.UTF_8), "orders");
    reader.decrypt(e, "orders");
    clock.now = clock.now.plus(Duration.ofMinutes(4));
    reader.decrypt(e, "orders");
    assertEquals(1, kms.decrypted.get(), "within the age the key is reused");
    clock.now = clock.now.plus(Duration.ofMinutes(1));
    reader.decrypt(e, "orders");
    assertEquals(2, kms.decrypted.get(), "at the age limit it is asked again");
  }

  @Test
  void eachCacheKeepsABoundedNumberOfKeys() {
    FakeKms kms = new FakeKms();
    EnvelopeEncryptor env =
        new EnvelopeEncryptor(kms, Duration.ofHours(1), 1_000, Clock.systemUTC());
    byte[] first = env.encrypt("x".getBytes(StandardCharsets.UTF_8), "ctx-0");
    for (int i = 1; i <= EnvelopeEncryptor.MAX_CACHED_KEYS; i++) {
      env.encrypt("x".getBytes(StandardCharsets.UTF_8), "ctx-" + i);
    }
    int generated = kms.generated.get();
    env.encrypt("x".getBytes(StandardCharsets.UTF_8), "ctx-0");
    assertEquals(generated + 1, kms.generated.get(), "the least recently used context left");

    env.decrypt(first, "ctx-0");
    for (int i = 1; i <= EnvelopeEncryptor.MAX_CACHED_KEYS; i++) {
      byte[] wrapped = ("other-" + i).getBytes(StandardCharsets.UTF_8);
      byte[] key = AesGcm.newKey().getEncoded();
      kms.register(wrapped, key);
      env.decrypt(envelope(wrapped, key, "v"), "account");
    }
    int decrypted = kms.decrypted.get();
    env.decrypt(first, "ctx-0");
    assertEquals(decrypted + 1, kms.decrypted.get(), "the least recently used key left");
  }
}
