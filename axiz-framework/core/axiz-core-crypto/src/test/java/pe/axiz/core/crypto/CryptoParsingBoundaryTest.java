package pe.axiz.core.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class CryptoParsingBoundaryTest {
  private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

  @Test
  void persistedIndexesRejectUnboundProfilesAndNoncanonicalOrMalformedPayloads() {
    var scope = new IndexScope("search", "DNI", "tenant", "domain", "profile", 1);
    var index = new KeyedHash("k1", scope, new byte[32]);
    assertEquals("****", index.masked());
    assertEquals(1, index.formatVersion());
    assertEquals(index.hashCode(), KeyedHash.parse(index.encoded()).hashCode());
    assertThrows(CryptoException.class, () -> KeyedHash.parse(null));
    for (String invalid :
        List.of(
            "axi1:k1::1:" + URL.encodeToString(new byte[32]),
            "axi1:bad.key:cHJvZmlsZQ:1:" + URL.encodeToString(new byte[32]),
            "axi1:k1:!:1:" + URL.encodeToString(new byte[32])))
      assertThrows(CryptoException.class, () -> KeyedHash.parse(invalid));
    assertNotEquals(index, "index");
    assertNotEquals(index, new KeyedHash("k2", scope, new byte[32]));
    assertNotEquals(
        index,
        new KeyedHash(
            "k1", new IndexScope("search", "DNI", "tenant", "domain", "other", 1), new byte[32]));
    assertNotEquals(
        index,
        new KeyedHash(
            "k1", new IndexScope("search", "DNI", "tenant", "domain", "profile", 2), new byte[32]));
    assertThrows(CryptoException.class, () -> new KeyedHash("k1", scope, new byte[31]));
  }

  @Test
  void encryptedFieldsHaveBoundedCanonicalSyntaxAndValueIdentityWithoutPlaintextExposure() {
    String text = "axz1:k1:" + URL.encodeToString(new byte[28]);
    var first = EncryptedValue.parse(text);
    var second = EncryptedValue.parse(text);
    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
    assertEquals("****", first.masked());
    assertEquals("EncryptedValue[****]", first.toString());
    assertNotEquals(first, text);
    assertNotEquals(first, EncryptedValue.parse("axz1:k2:" + URL.encodeToString(new byte[28])));
    assertThrows(CryptoException.class, () -> EncryptedValue.parse(null));
    assertThrows(CryptoException.class, () -> EncryptedValue.parse("axz1:k1:A"));
    assertThrows(
        CryptoException.class,
        () -> EncryptedValue.parse("axz1:k1:" + URL.encodeToString(new byte[1_000_029])));
    assertEquals(
        "k1", EncryptedValue.parse("axz1:k1:" + URL.encodeToString(new byte[1_000_028])).keyId());
  }

  @Test
  void technicalPayloadFramesRejectSizeVersionAndEncodingConfusionBeforeCryptography() {
    assertThrows(CryptoException.class, () -> PayloadProtection.binding(" ", "row"));
    assertThrows(
        CryptoException.class,
        () -> PayloadProtection.plaintext(new byte[PayloadProtection.MAX_PLAIN + 1]));
    assertEquals(
        PayloadProtection.MAX_PLAIN,
        PayloadProtection.plaintext(new byte[PayloadProtection.MAX_PLAIN]).length);
    int maximumStored = PayloadProtection.MAX_PLAIN + 65_600;
    assertThrows(CryptoException.class, () -> PayloadProtection.frame(new byte[maximumStored - 4]));
    for (byte[] invalid : List.of(new byte[5], new byte[6], new byte[maximumStored + 1]))
      assertThrows(CryptoException.class, () -> PayloadProtection.unframe(invalid));
    for (String invalid : List.of("other:AA", "axzp1:!", "axzp1:AA=", "axzp1:AB"))
      assertThrows(CryptoException.class, () -> PayloadProtection.parse(invalid));
    assertThrows(
        CryptoException.class,
        () -> PayloadProtection.parse("axzp1:" + "A".repeat(((maximumStored + 2) / 3) * 4 + 1)));
    assertArrayEquals(new byte[] {0}, PayloadProtection.parse("axzp1:AA"));
    assertArrayEquals(
        new byte[] {0}, PayloadProtection.unframe(PayloadProtection.frame(new byte[] {0})));
    assertThrows(
        CryptoException.class, () -> PayloadProtection.utf8(new byte[] {(byte) 0xc3, 0x28}));
  }
}
