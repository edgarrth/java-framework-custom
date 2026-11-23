package pe.axiz.core.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.DataKeyProvider.DataKey;
import pe.axiz.core.secrets.SecretValue;

class CryptoWireLimitsTest {
  @Test
  void keyRoutingAcceptsInclusiveWireLimitsAndRejectsInvalidReadIdentifiers() {
    for (String id : List.of("k", "k".repeat(32))) {
      for (int size : new int[] {1, 65535 - 7 - id.length()}) {
        byte[] wrapped = new byte[size];
        Arrays.fill(wrapped, (byte) 7);
        try (var raw = new DataKey(new byte[32], wrapped);
            var routed = DataKeyRouting.routed(raw, id)) {
          var parsed = DataKeyRouting.parse(routed.encrypted());
          assertEquals(id, parsed.keyId());
          assertArrayEquals(wrapped, parsed.encrypted());
        }
      }
    }
    try (var max = new DataKey(new byte[32], new byte[65535])) {
      assertTrue(max.validForEnvelope());
    }
    var providers = new HashMap<String, String>();
    for (int i = 0; i < 16; i++) providers.put("k" + i, "provider");
    assertEquals(16, DataKeyRouting.providers(providers, "k0").size());
    providers.put("k16", "provider");
    assertThrows(CryptoException.class, () -> DataKeyRouting.providers(providers, "k0"));
    assertThrows(
        CryptoException.class,
        () -> DataKeyRouting.providers(Map.of("bad.key", "provider"), "bad.key"));
  }

  @Test
  void everyUtf8WidthAndAuthenticatedPartCountHasInclusiveLimits() {
    for (String value : List.of("", "x", "é", "€", "😀")) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      assertArrayEquals(bytes, StrictUtf8.encode(value, bytes.length));
      assertEquals(value, StrictUtf8.decode(bytes, bytes.length));
      if (bytes.length > 0) {
        assertThrows(CryptoException.class, () -> StrictUtf8.encode(value, bytes.length - 1));
        assertThrows(CryptoException.class, () -> StrictUtf8.decode(bytes, bytes.length - 1));
      }
    }
    assertTrue(Aad.of(Aad.FIELD, "1", "2", "3", "4", "5", "6", "7", "8").length > 0);
    try (var index = new BlindIndex(Map.of("k", new byte[1024]), "k")) {
      assertEquals(
          32,
          index
              .index("v".repeat(8192), new IndexScope("p", "t", "a", "d", "i", 1))
              .digest()
              .length);
    }
    assertThrows(CryptoException.class, () -> new BlindIndex(Map.of("k", new byte[1025]), "k"));
  }

  @Test
  void allIdentityOverloadsPreserveOwnershipAndRejectMissingBindingsAndReservedBytes() {
    byte[] input = {7, 8, 9};
    byte[] restored = PayloadProtector.NONE.reveal(input, "c", "b");
    restored[0] = 0;
    assertEquals(7, input[0]);
    assertEquals("raw", PayloadProtector.NONE.protect("raw", "c", "b"));
    assertEquals(
        "raw", AsyncPayloadProtector.NONE.protect("raw", "c", "b").toCompletableFuture().join());
    assertArrayEquals(
        input, AsyncPayloadProtector.NONE.protect(input, "c", "b").toCompletableFuture().join());
    assertArrayEquals(
        input, AsyncPayloadProtector.NONE.reveal(input, "c", "b").toCompletableFuture().join());
    assertThrows(CryptoException.class, () -> PayloadProtector.NONE.protect(input, "c", ""));
    assertThrows(
        CryptoException.class,
        () -> PayloadProtector.NONE.reveal(new byte[] {'A', 'X', 'Z', 'P'}, "c", "b"));
    assertThrows(
        CompletionException.class,
        () -> AsyncPayloadProtector.NONE.protect(input, "c", "").toCompletableFuture().join());
    var keys = new EnvelopeKeyCacheTest.FakeKms();
    try (var sync = new EnvelopeEncryptor(keys, Duration.ofMinutes(1), 10, Clock.systemUTC());
        var async =
            new AsyncEnvelopeEncryptor(
                KeyringDataKeyProviderTest.nativeKeys(keys),
                Duration.ofMinutes(1),
                10,
                Clock.systemUTC())) {
      var imperative = new EnvelopePayloadProtector(sync);
      var nativePort = new AsyncEnvelopePayloadProtector(async);
      // Un envelope de bajo nivel válido con enlace vacío no debe saltarse la política del puerto
      // de almacenamiento.
      byte[] unbound = PayloadProtection.frame(sync.encrypt(input, "c", ""));
      assertThrows(CryptoException.class, () -> imperative.protect(input, "c", ""));
      assertThrows(CryptoException.class, () -> imperative.reveal(unbound, "c", ""));
      assertThrows(
          CompletionException.class,
          () -> nativePort.protect(input, "c", "").toCompletableFuture().join());
      assertThrows(
          CompletionException.class,
          () -> nativePort.reveal(unbound, "c", "").toCompletableFuture().join());
    }
  }

  @Test
  void maximumPayloadFrameAndSmallestFrameRoundTripWithoutTruncation() {
    int max = PayloadProtection.MAX_PLAIN + 65_600;
    byte[] envelope = new byte[max - 5];
    envelope[envelope.length - 1] = 42;
    byte[] stored = PayloadProtection.frame(envelope);
    assertEquals(max, stored.length);
    assertArrayEquals(envelope, PayloadProtection.unframe(stored));
    assertArrayEquals(stored, PayloadProtection.parse(PayloadProtection.text(stored)));
    assertArrayEquals(
        new byte[] {42}, PayloadProtection.unframe(PayloadProtection.frame(new byte[] {42})));
  }

  @Test
  void independentlyCalculatedDniIndexMatchesTheCombinedOperationAndMetadataSurvivesStorage() {
    var scope = new FieldScope("tenant", "domain");
    var indexScope = new IndexScope("search", "DNI_PE", "tenant", "domain", "dni-pe", 1);
    try (var keys = StaticKeyProvider.fromBytes("e", Map.of("e", new byte[32]));
        var index = new BlindIndex(Map.of("i", new byte[32]), "i");
        var value = SecretValue.of("00123456")) {
      var cipher = new FieldEncryptor(keys);
      var port = new FieldProtector(cipher, index, FieldProtectionPolicy.dniPe("search"));
      var result = port.protect(value, scope, "row");
      assertEquals(index.index("00123456", indexScope), result.index());
      assertNotEquals(index.index("00999999", indexScope), result.index());
      assertEquals("****", result.masked());
      var loaded = KeyedHash.parse(result.index().encoded());
      assertEquals("dni-pe", loaded.profileId());
      assertEquals(1, loaded.canonicalVersion());
      assertNotEquals(0, loaded.hashCode());
      assertNotEquals(0, result.ciphertext().hashCode());
      assertFalse(cipher.isEncrypted("raw"));
      assertFalse(cipher.isEncrypted("axz1:bad"));
      assertTrue(cipher.isEncrypted(result.ciphertext().encoded()));
    }
    var sync =
        new KeyringDataKeyProvider(Map.of("active", new EnvelopeKeyCacheTest.FakeKms()), "active");
    var async =
        new KeyringAsyncDataKeyProvider(
            Map.of(
                "active",
                KeyringDataKeyProviderTest.nativeKeys(new EnvelopeKeyCacheTest.FakeKms())),
            "active");
    assertEquals("active", sync.writeKeyId());
    assertEquals("active", async.writeKeyId());
    assertEquals(Set.of("active"), async.readKeyIds());
    assertThrows(UnsupportedOperationException.class, () -> async.readKeyIds().clear());
  }

  @Test
  void keyStrengthAndMalformedSignatureAndPasswordMigrationDefaultsAreSafe() {
    assertEquals(32, AesGcm.newKey().getEncoded().length);
    var pair = Rsa.newKeyPair(2048);
    assertFalse(Rsa.verify(pair.getPublic(), new byte[] {1}, new byte[] {1}));
    PasswordHasher defaults =
        new PasswordHasher() {
          public String hash(char[] password) {
            throw new UnsupportedOperationException();
          }

          public boolean matches(char[] password, String encoded) {
            return false;
          }
        };
    assertFalse(defaults.needsUpgrade("custom-format"));
    String suffix = "$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    assertEquals(
        10_000_000,
        Pbkdf2PasswordHasher.parse("$pbkdf2-sha256$i=010000000" + suffix)
            .orElseThrow()
            .iterations());
  }
}
