package pe.axiz.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CryptoBoundaryInvariantsTest {
  @Test
  void contextsMustBeExplicitAndRebindingAuthenticatesTheNewPurpose() {
    for (String invalid :
        new String[] {
          "axiz.field", "generic", "account.number\n", "purpose:", "entity." + "a".repeat(250)
        }) assertThrows(IllegalArgumentException.class, () -> EncryptionContext.require(invalid));
    for (String valid :
        new String[] {
          "account.number", "purpose:shared", "account.number:row-1", "entity." + "a".repeat(249)
        }) assertEquals(valid, EncryptionContext.require(valid));
    var provider = new StaticKeyProvider("k", Map.of("k", AesGcm.newKey()));
    assertEquals(provider.key("k"), provider.currentKey());
    assertTrue(provider.find("k").isPresent());
    assertTrue(provider.find("unknown").isEmpty());
    var encryptor = new FieldEncryptor(provider);
    String original = encryptor.encrypt("sensitive", "account.oldNumber");
    assertFalse(encryptor.needsRotation(original));
    String rebound = encryptor.reencrypt(original, "account.oldNumber", "account.number");
    assertEquals("sensitive", encryptor.decrypt(rebound, "account.number"));
    assertThrows(CryptoException.class, () -> encryptor.decrypt(rebound, "account.oldNumber"));
    assertNull(encryptor.reencrypt(null, "account.oldNumber", "account.number"));
  }

  @Test
  void authenticationRejectsTamperedMacAndSignatureAndHonorsTheRequestedKeySize() {
    byte[] data = "operation".getBytes(StandardCharsets.UTF_8);
    byte[] key = new byte[32];
    byte[] mac = Hashing.hmacSha256(key, data);
    assertTrue(Hashing.verifyHmacSha256(key, data, mac));
    mac[0] ^= 1;
    assertFalse(Hashing.verifyHmacSha256(key, data, mac));
    assertEquals(32, AesGcm.newKey().getEncoded().length);
    var rsa = Rsa.newKeyPair(4096);
    assertEquals(4096, ((RSAPublicKey) rsa.getPublic()).getModulus().bitLength());
    byte[] signature = Rsa.sign(rsa.getPrivate(), data);
    assertTrue(Rsa.verify(rsa.getPublic(), data, signature));
    assertFalse(Rsa.verify(rsa.getPublic(), "other".getBytes(StandardCharsets.UTF_8), signature));
    assertThrows(CryptoException.class, () -> new BlindIndex(key, 7));
    assertThrows(CryptoException.class, () -> new BlindIndex(key, 33));
    assertNotNull(new BlindIndex(key, 8).index("value", "purpose:test"));
    assertNotNull(new BlindIndex(key, 32).index("value", "purpose:test"));
  }

  @Test
  void exactEnvelopeBoundariesRemainAuthenticAndRejectMalformedLengths() {
    byte[] key = AesGcm.newKey().getEncoded();
    for (int length : new int[] {1, 65535}) {
      byte[] encryptedKey = new byte[length];
      byte[] envelope = EnvelopePayload.seal(new byte[0], key, encryptedKey, "purpose:test");
      var parsed = EnvelopePayload.parse(envelope);
      assertEquals(length, parsed.encryptedKey().length);
      assertEquals(0, parsed.open(key, "purpose:test").length);
      assertThrows(CryptoException.class, () -> parsed.open(key, "purpose:other"));
      assertThrows(
          CryptoException.class,
          () -> EnvelopePayload.parse(Arrays.copyOf(envelope, envelope.length - 1)));
    }
    assertThrows(
        CryptoException.class,
        () -> EnvelopePayload.seal(new byte[0], key, new byte[0], "purpose:test"));
    assertThrows(
        CryptoException.class,
        () -> EnvelopePayload.seal(new byte[0], key, new byte[65536], "purpose:test"));
    EnvelopePayload.validatePolicy(Duration.ofSeconds(1), 1);
    assertThrows(
        IllegalArgumentException.class, () -> EnvelopePayload.validatePolicy(Duration.ZERO, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> EnvelopePayload.validatePolicy(Duration.ofSeconds(1), 0));
  }
}
