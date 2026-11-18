package pe.axiz.core.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.secrets.SecretValue;

class FieldProtectionSecurityTest {
  private static final IndexScope SCOPE =
      new IndexScope("search", "DNI_PE", "t1", "customer", "identity", 1);
  private final BlindIndex index = new BlindIndex(Map.of("i1", new byte[32]), "i1");
  private final FieldEncryptor cipher =
      new FieldEncryptor(new StaticKeyProvider("e1", Map.of("e1", AesGcm.newKey())));
  private final FieldScope scope = new FieldScope("t1", "customer");

  @Test
  void framingHasNoDelimiterOrMalformedUnicodeAliases() {
    IndexScope shifted = new IndexScope("search|DNI_PE", "t1", "customer", "identity", "x", 1);
    assertNotEquals(index.index("x|00123456", SCOPE), index.index("00123456", shifted));
    assertNotEquals(index.index("é", SCOPE), index.index("e\u0301", SCOPE));
    assertNotEquals(index.index("Ab-c", SCOPE), index.index("ABC", SCOPE));
    assertNotEquals(index.index(" 00123456 ", SCOPE), index.index("00123456", SCOPE));
    assertThrows(CryptoException.class, () -> index.index("\ud800", SCOPE));
    assertThrows(CryptoException.class, () -> index.index("x".repeat(8193), SCOPE));
    assertThrows(CryptoException.class, () -> cipher.encrypt("\ud800", "customer.dni"));
    assertThrows(
        CryptoException.class, () -> new IndexScope("search", "DNI", "\ud800", "d", "p", 1));
    assertEquals(32, index.index("00123456", SCOPE).digest().length);
  }

  @Test
  void keysAreDefensivelyCopiedAndRotationUsesCurrentFirstThenAllActiveReadKeys() {
    byte[] first = new byte[32];
    byte[] second = new byte[32];
    second[0] = 1;
    var rotated = new BlindIndex(Map.of("i1", first, "i2", second), "i2");
    var before = rotated.candidates("00123456", SCOPE);
    first[0] = 20;
    second[0] = 20;
    assertEquals(before, rotated.candidates("00123456", SCOPE));
    assertEquals("i2", before.getFirst().keyId());
    assertEquals(index.index("00123456", SCOPE), before.getLast());
    byte[] escaped = before.getFirst().digest();
    escaped[0] ^= 1;
    assertFalse(java.util.Arrays.equals(escaped, before.getFirst().digest()));
    assertThrows(UnsupportedOperationException.class, () -> rotated.keyIds().clear());
    assertThrows(CryptoException.class, () -> rotated.index("00123456", SCOPE, "retired"));
    assertFalse(before.toString().contains(before.getFirst().encoded()));
  }

  @Test
  void protectionPreservesOriginalAndSearchWorksWithoutKnowingRowIds() {
    var protector = new FieldProtector(cipher, index, FieldProtectionPolicy.dniPe("search"));
    try (SecretValue original = SecretValue.of("00123456")) {
      ProtectedField first = protector.protect(original, scope, "row-1");
      ProtectedField second = protector.protect(original, scope, "row-2");
      assertEquals(first.index(), second.index());
      assertEquals(first.index(), protector.index(original, scope));
      assertNotEquals(first.ciphertext(), second.ciphertext());
      assertTrue(protector.candidates(original, scope).contains(first.index()));
      EncryptedValue loaded = EncryptedValue.parse(first.ciphertext().encoded());
      try (SecretValue revealed = protector.decrypt(loaded, scope, "row-1")) {
        assertTrue(original.matches(revealed));
      }
      assertThrows(CryptoException.class, () -> protector.decrypt(loaded, scope, "row-2"));
      assertThrows(
          CryptoException.class,
          () -> protector.decrypt(loaded, new FieldScope("t2", "customer"), "row-1"));
      var otherField =
          new FieldProtector(cipher, index, FieldProtectionPolicy.identity("search", "PASSPORT"));
      assertThrows(CryptoException.class, () -> otherField.decrypt(loaded, scope, "row-1"));
      assertEquals("ProtectedField[****]", first.toString());
    }
  }

  @Test
  void explicitCanonicalizationChangesOnlyTheIndexAndCiphertextTamperingIsRejected() {
    var protector =
        new FieldProtector(
            cipher,
            index,
            new FieldProtectionPolicy("search", "OPAQUE", "strip", 2, String::strip));
    try (SecretValue original = SecretValue.of("  A-bé  ");
        SecretValue equivalent = SecretValue.of("A-bé")) {
      var stored = protector.protect(original, scope, "row-1");
      assertEquals(stored.index(), protector.index(equivalent, scope));
      try (SecretValue restored = protector.decrypt(stored.ciphertext(), scope, "row-1")) {
        assertTrue(original.matches(restored));
      }
      String[] parts = stored.ciphertext().encoded().split(":");
      byte[] payload = Base64.getUrlDecoder().decode(parts[2]);
      payload[payload.length - 1] ^= 1;
      var tampered =
          EncryptedValue.parse(
              parts[0]
                  + ":"
                  + parts[1]
                  + ":"
                  + Base64.getUrlEncoder().withoutPadding().encodeToString(payload));
      assertThrows(CryptoException.class, () -> protector.decrypt(tampered, scope, "row-1"));
      assertThrows(CryptoException.class, () -> EncryptedValue.parse("axz2:e1:xxx"));
    }
    assertArrayEquals(Aad.of(Aad.FIELD, "a|b", "c"), Aad.of(Aad.FIELD, "a|b", "c"));
    assertFalse(
        java.util.Arrays.equals(Aad.of(Aad.FIELD, "a|b", "c"), Aad.of(Aad.FIELD, "a", "b|c")));
    assertThrows(CryptoException.class, () -> Aad.of(Aad.FIELD, "\ud800"));
  }
}
