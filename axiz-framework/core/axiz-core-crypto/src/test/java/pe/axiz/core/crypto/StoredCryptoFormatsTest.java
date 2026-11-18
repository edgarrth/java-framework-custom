package pe.axiz.core.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StoredCryptoFormatsTest {
  @Test
  void fieldReadersShareCanonicalBoundedSyntaxBeforeResolvingAnyKey() {
    try (var keys = StaticKeyProvider.fromBytes("k1", Map.of("k1", new byte[32]))) {
      var fields = new FieldEncryptor(keys);
      String valid = fields.encrypt("00123456", "customer.dni", "row-1");
      assertTrue(fields.isEncrypted(valid));
      assertEquals("k1", EncryptedValue.parse(valid).keyId());
      assertEquals("00123456", fields.decrypt(valid, "customer.dni", "row-1"));
      assertFalse(fields.needsRotation(valid));
      String zeros = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[28]);
      for (String invalid :
          List.of(
              "axz2:k1:" + zeros,
              "axz1::" + zeros,
              "axz1:bad id:" + zeros,
              "axz1:k1:",
              "axz1:k1:AA",
              "axz1:k1:" + zeros + "=",
              "axz1:k1:" + zeros + ":extra",
              "axz1:k1:" + zeros.substring(0, zeros.length() - 1) + "B",
              "axz1:k1:" + "A".repeat(1_400_001))) {
        assertFalse(fields.isEncrypted(invalid));
        assertThrows(CryptoException.class, () -> EncryptedValue.parse(invalid));
        assertThrows(CryptoException.class, () -> fields.decrypt(invalid, "customer.dni"));
        assertThrows(CryptoException.class, () -> fields.needsRotation(invalid));
      }
      assertThrows(CryptoException.class, () -> fields.decrypt(valid, "customer.dni", "row-2"));
    }
  }

  @Test
  void authenticatedButMalformedUtf8IsRejectedInsteadOfSilentlyChangingTheValue() {
    try (var keys = StaticKeyProvider.fromBytes("k", Map.of("k", new byte[32]))) {
      byte[] encrypted =
          AesGcm.encrypt(
              keys.key("k"),
              new byte[] {(byte) 0xc3, 0x28},
              Aad.of(Aad.FIELD, "customer.dni", "k"));
      String stored = "axz1:k:" + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
      assertThrows(
          CryptoException.class, () -> new FieldEncryptor(keys).decrypt(stored, "customer.dni"));
    }
  }

  @Test
  void indexStorageRoundTripsUnicodeProfilesAndRejectsAlternateOrTruncatedRepresentations() {
    try (var index = new BlindIndex(Map.of("i1", new byte[32]), "i1")) {
      var original =
          index.index(
              "00123456", new IndexScope("field", "DNI", "tenant", "domain", "pe:identidad-á", 7));
      String stored = original.encoded();
      var parsed = KeyedHash.parse(stored);
      assertEquals(original, parsed);
      assertEquals(stored, parsed.encoded());
      assertEquals("KeyedHash[****]", parsed.toString());
      byte[] digest = parsed.digest();
      Arrays.fill(digest, (byte) 0);
      assertEquals(original, parsed);
      for (String invalid :
          List.of(
              stored.replace("axi1:", "axi2:"),
              stored.replace(":7:", ":07:"),
              stored.replace(":7:", ":0:"),
              stored.replace(":7:", ":2147483648:"),
              stored + "=",
              stored + ":extra",
              stored.substring(0, stored.length() - 1),
              "axi1:i1:ww:1:"
                  + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]),
              "x".repeat(1501))) {
        assertThrows(CryptoException.class, () -> KeyedHash.parse(invalid));
      }
      var differentScope =
          index.index(
              "00123456", new IndexScope("field", "DNI", "another", "domain", "pe:identidad-á", 7));
      assertFalse(parsed.equals(differentScope));
    }
  }

  @Test
  void closingOwnedKeyringsDoesNotDestroyCallerBuffersOrOtherInstances() {
    byte[] key = new byte[32];
    new java.security.SecureRandom().nextBytes(key);
    byte[] original = key.clone();
    var first = StaticKeyProvider.fromBytes("k", Map.of("k", key));
    try (var second = StaticKeyProvider.fromBytes("k", Map.of("k", key))) {
      first.close();
      assertThrows(CryptoException.class, () -> first.key("k"));
      assertArrayEquals(original, key);
      assertArrayEquals(original, second.key("k").getEncoded());
    }
    var index = new BlindIndex(Map.of("i", key), "i");
    index.close();
    assertThrows(
        CryptoException.class,
        () -> index.index("value", new IndexScope("field", "DNI", "t", "d", "identity", 1)));
    assertArrayEquals(original, key);
  }
}
