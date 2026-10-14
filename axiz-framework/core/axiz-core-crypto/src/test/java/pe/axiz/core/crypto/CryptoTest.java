package pe.axiz.core.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

class CryptoTest {

  private static final byte[] DATA = "4111111111111111".getBytes(StandardCharsets.UTF_8);

  @Test
  void aesGcmRoundTripWithRandomIvAndAad() {
    SecretKey key = AesGcm.newKey();
    byte[] a = AesGcm.encrypt(key, DATA, "ctx".getBytes(StandardCharsets.UTF_8));
    byte[] b = AesGcm.encrypt(key, DATA, "ctx".getBytes(StandardCharsets.UTF_8));
    assertFalse(java.util.Arrays.equals(a, b), "IV must be random");
    assertArrayEquals(DATA, AesGcm.decrypt(key, a, "ctx".getBytes(StandardCharsets.UTF_8)));
    assertThrows(
        CryptoException.class,
        () -> AesGcm.decrypt(key, a, "other".getBytes(StandardCharsets.UTF_8)));
    a[20] ^= 1;
    assertThrows(
        CryptoException.class,
        () -> AesGcm.decrypt(key, a, "ctx".getBytes(StandardCharsets.UTF_8)));
    assertThrows(CryptoException.class, () -> AesGcm.decrypt(key, new byte[5], null));
    assertThrows(CryptoException.class, () -> AesGcm.key(new byte[16]));
    assertArrayEquals(DATA, AesGcm.decrypt(key, AesGcm.encrypt(key, DATA, null), null));
  }

  @Test
  void fieldEncryptionSupportsRotationAndContextBinding() {
    byte[] k1 = new byte[32];
    byte[] k2 = new byte[32];
    k2[0] = 1;
    String b1 = Base64.getEncoder().encodeToString(k1);
    String b2 = Base64.getEncoder().encodeToString(k2);
    FieldEncryptor v1 = new FieldEncryptor(StaticKeyProvider.fromBase64("k1", Map.of("k1", b1)));
    String stored = v1.encrypt("12345678", "customer.document");
    assertTrue(stored.startsWith("axz1:k1:"));
    assertTrue(v1.isEncrypted(stored));
    FieldEncryptor v2 =
        new FieldEncryptor(StaticKeyProvider.fromBase64("k2", Map.of("k1", b1, "k2", b2)));
    assertEquals("12345678", v2.decrypt(stored, "customer.document"));
    assertTrue(v2.needsRotation(stored));
    assertFalse(v2.needsRotation(v2.encrypt("x", "c")));
    assertThrows(CryptoException.class, () -> v2.decrypt(stored, "customer.email"));
    assertThrows(CryptoException.class, () -> v2.decrypt("plain", "c"));
    assertThrows(CryptoException.class, () -> v2.decrypt("axz1:k1:%%%", "c"));
    assertNull(v2.encrypt(null, "c"));
    assertNull(v2.decrypt(null, "c"));
    assertThrows(CryptoException.class, () -> StaticKeyProvider.fromBase64("k3", Map.of("k1", b1)));
    assertThrows(
        CryptoException.class,
        () -> new StaticKeyProvider("bad id", Map.of("bad id", AesGcm.newKey())));
    assertTrue(
        v2.encrypt("x", "c") != null
            && new StaticKeyProvider("k", Map.of("k", AesGcm.newKey())).find("z").isEmpty());
  }

  @Test
  void envelopeEncryptionCachesDataKeys() {
    SecretKey master = AesGcm.newKey();
    AtomicInteger generated = new AtomicInteger();
    AtomicInteger decrypted = new AtomicInteger();
    DataKeyProvider fakeKms =
        new DataKeyProvider() {
          @Override
          public DataKey generate(String context) {
            generated.incrementAndGet();
            byte[] dk = AesGcm.newKey().getEncoded();
            return new DataKey(
                dk, AesGcm.encrypt(master, dk, context.getBytes(StandardCharsets.UTF_8)));
          }

          @Override
          public byte[] decrypt(byte[] encryptedKey, String context) {
            decrypted.incrementAndGet();
            return AesGcm.decrypt(master, encryptedKey, context.getBytes(StandardCharsets.UTF_8));
          }
        };
    EnvelopeEncryptor env =
        new EnvelopeEncryptor(fakeKms, Duration.ofMinutes(5), 2, Clock.systemUTC());
    byte[] e1 = env.encrypt(DATA, "orders");
    byte[] e2 = env.encrypt(DATA, "orders");
    byte[] e3 = env.encrypt(DATA, "orders");
    assertEquals(2, generated.get());
    assertArrayEquals(DATA, env.decrypt(e1, "orders"));
    assertArrayEquals(DATA, env.decrypt(e2, "orders"));
    assertArrayEquals(DATA, env.decrypt(e3, "orders"));
    assertEquals(2, decrypted.get());
    assertThrows(CryptoException.class, () -> env.decrypt(new byte[2], "orders"));
    assertThrows(CryptoException.class, () -> env.decrypt(new byte[] {0, 0, 1, 2}, "orders"));
    assertEquals("DataKey[****]", new DataKeyProvider.DataKey(new byte[1], new byte[1]).toString());
  }

  @Test
  void rsaOaepAndPss() {
    KeyPair kp = Rsa.newKeyPair(2048);
    assertArrayEquals(DATA, Rsa.decrypt(kp.getPrivate(), Rsa.encrypt(kp.getPublic(), DATA)));
    byte[] sig = Rsa.sign(kp.getPrivate(), DATA);
    assertTrue(Rsa.verify(kp.getPublic(), DATA, sig));
    assertFalse(Rsa.verify(kp.getPublic(), "x".getBytes(StandardCharsets.UTF_8), sig));
    assertThrows(CryptoException.class, () -> Rsa.newKeyPair(1024));
    assertThrows(CryptoException.class, () -> Rsa.decrypt(kp.getPrivate(), new byte[10]));
  }

  @Test
  void hashingAndBlindIndex() {
    assertEquals(
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Hashing.sha256Hex(""));
    assertEquals(64, Hashing.sha512(DATA).length);
    byte[] key = new byte[32];
    byte[] mac = Hashing.hmacSha256(key, DATA);
    assertTrue(Hashing.verifyHmacSha256(key, DATA, mac));
    assertEquals(64, Hashing.hmacSha512(key, DATA).length);
    assertThrows(CryptoException.class, () -> Hashing.hmacSha256(new byte[4], DATA));
    BlindIndex bi = new BlindIndex(key, 16);
    assertEquals(bi.index(" 1234-5678 ", "doc"), bi.index("12345678", "doc"));
    assertNotEquals(bi.index("12345678", "doc"), bi.index("12345678", "account"));
    assertThrows(CryptoException.class, () -> new BlindIndex(key, 4));
    assertThrows(CryptoException.class, () -> new BlindIndex(new byte[1], 16));
  }

  @Test
  void pbkdf2Passwords() {
    Pbkdf2PasswordHasher hasher = new Pbkdf2PasswordHasher(100_000);
    String hash = hasher.hash("s3cret".toCharArray());
    assertTrue(hasher.matches("s3cret".toCharArray(), hash));
    assertFalse(hasher.matches("wrong".toCharArray(), hash));
    assertFalse(hasher.matches("s3cret".toCharArray(), "$2a$10$bcrypt"));
    assertFalse(hasher.matches("s3cret".toCharArray(), "$pbkdf2-sha256$i=x$y"));
    assertTrue(new Pbkdf2PasswordHasher(200_000).needsUpgrade(hash));
    assertFalse(hasher.needsUpgrade(hash));
    assertTrue(hasher.needsUpgrade("$argon2id$..."));
    assertThrows(CryptoException.class, () -> new Pbkdf2PasswordHasher(10));
  }
}
