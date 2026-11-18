package pe.axiz.core.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.DataKeyProvider.DataKey;

class KeyAndScopeBoundaryTest {
  @Test
  void externalMonitorsCannotPreventKeyUseOrRevocation() throws Exception {
    try (var provider = StaticKeyProvider.fromBytes("k", Map.of("k", new byte[32]));
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      synchronized (provider) {
        executor
            .submit(
                () -> {
                  assertArrayEquals(new byte[32], provider.key("k").getEncoded());
                  assertTrue(provider.containsKeyMaterial(new byte[32]));
                  provider.close();
                })
            .get(5, TimeUnit.SECONDS);
      }
      assertThrows(CryptoException.class, () -> provider.key("k"));
    }
  }

  @Test
  void staticKeyRingRejectsInvalidSizesAlgorithmsEncodingAndMissingWriteKeys() {
    assertThrows(CryptoException.class, () -> StaticKeyProvider.fromBytes("key", Map.of()));
    assertThrows(
        CryptoException.class,
        () -> StaticKeyProvider.fromBytes("missing", Map.of("key", new byte[32])));
    var keys = new HashMap<String, byte[]>();
    for (int i = 0; i < 17; i++) keys.put("k" + i, new byte[32]);
    assertThrows(CryptoException.class, () -> StaticKeyProvider.fromBytes("k0", keys));
    keys.remove("k16");
    try (var accepted = StaticKeyProvider.fromBytes("k0", keys)) {
      assertArrayEquals(new byte[32], accepted.currentKey().getEncoded());
      assertTrue(accepted.containsKeyMaterial(new byte[32]));
      assertFalse(accepted.containsKeyMaterial(new byte[31]));
      assertThrows(CryptoException.class, () -> accepted.key("missing"));
    }
    for (String invalid : List.of("", "bad.key", "k".repeat(33)))
      assertThrows(
          CryptoException.class,
          () -> StaticKeyProvider.fromBytes(invalid, Map.of(invalid, new byte[32])));
    assertThrows(
        CryptoException.class,
        () -> StaticKeyProvider.fromBytes("key", Map.of("key", new byte[31])));
    assertThrows(
        CryptoException.class,
        () ->
            new StaticKeyProvider(
                "key", Map.of("key", new SecretKeySpec(new byte[32], "HmacSHA256"))));
    SecretKey handle =
        new SecretKey() {
          private static final long serialVersionUID = 1L;

          @Override
          public String getAlgorithm() {
            return "AES";
          }

          @Override
          public String getFormat() {
            return "RAW";
          }

          @Override
          public byte[] getEncoded() {
            return null;
          }
        };
    assertThrows(CryptoException.class, () -> new StaticKeyProvider("key", Map.of("key", handle)));
    for (String invalid :
        List.of(
            "x".repeat(42),
            "x".repeat(45),
            "!".repeat(44),
            Base64.getEncoder().encodeToString(new byte[31])))
      assertThrows(
          CryptoException.class, () -> StaticKeyProvider.fromBase64("key", Map.of("key", invalid)));
    var provider =
        StaticKeyProvider.fromBase64(
            "key",
            Map.of("key", Base64.getEncoder().withoutPadding().encodeToString(new byte[32])));
    provider.close();
    assertThrows(CryptoException.class, () -> provider.key("key"));
    assertThrows(CryptoException.class, () -> provider.containsKeyMaterial(new byte[32]));
  }

  @Test
  void dataKeysRejectClosedIncompleteOrOversizedMaterialAndSnapshotsAreIndependent() {
    for (DataKey invalid :
        List.of(
            new DataKey(new byte[31], new byte[1]),
            new DataKey(new byte[32], new byte[0]),
            new DataKey(new byte[32], new byte[65536]))) {
      try (invalid) {
        assertThrows(CryptoException.class, invalid::validForEnvelope);
      }
    }
    var source = new DataKey(new byte[32], new byte[] {9});
    try (var snapshot = source.snapshot()) {
      source.close();
      assertThrows(CryptoException.class, source::plaintext);
      assertThrows(CryptoException.class, source::snapshot);
      assertThrows(CryptoException.class, source::validForEnvelope);
      assertTrue(snapshot.validForEnvelope());
      assertArrayEquals(new byte[32], snapshot.plaintext());
      assertEquals(source, snapshot);
      assertEquals(source.hashCode(), snapshot.hashCode());
      assertNotEquals(source, new DataKey(new byte[32], new byte[] {8}));
      assertNotEquals(source, "key");
    }
  }

  @Test
  void routedKeysRejectTruncationOverflowsUnknownFormatsAndNonAsciiIdentifiers() {
    try (var raw = new DataKey(new byte[32], new byte[] {1, 2});
        var routed = DataKeyRouting.routed(raw, "k1")) {
      byte[] bytes = routed.encrypted();
      assertEquals("k1", DataKeyRouting.parse(bytes).keyId());
      assertArrayEquals(new byte[] {1, 2}, DataKeyRouting.parse(bytes).encrypted());
      assertEquals("DataKeyRoute[****]", DataKeyRouting.parse(bytes).toString());
      for (int cut = 0; cut < bytes.length; cut++) {
        byte[] truncated = Arrays.copyOf(bytes, cut);
        assertThrows(CryptoException.class, () -> DataKeyRouting.parse(truncated));
      }
      for (int[] mutation :
          new int[][] {{0, 0}, {4, 0}, {4, 33}, {5, 255}, {5, '/'}, {8, 0}, {8, 3}}) {
        byte[] changed = bytes.clone();
        changed[mutation[0]] = (byte) mutation[1];
        assertThrows(CryptoException.class, () -> DataKeyRouting.parse(changed));
      }
      assertThrows(
          CryptoException.class,
          () -> DataKeyRouting.parse(Arrays.copyOf(bytes, bytes.length + 1)));
    }
    assertThrows(CryptoException.class, () -> DataKeyRouting.parse(new byte[65536]));
    assertThrows(CryptoException.class, () -> DataKeyRouting.validateId(null));
    assertThrows(CryptoException.class, () -> DataKeyRouting.context("k", " "));
    assertThrows(CryptoException.class, () -> DataKeyRouting.providers(Map.of(), "k"));
    assertThrows(
        CryptoException.class, () -> DataKeyRouting.providers(Map.of("k", "provider"), "unknown"));
    try (var huge = new DataKey(new byte[32], new byte[65535])) {
      assertThrows(CryptoException.class, () -> DataKeyRouting.routed(huge, "k"));
    }
    byte[] impossibleId = ByteBuffer.allocate(9).putInt(0x41584431).put((byte) 3).array();
    assertThrows(CryptoException.class, () -> DataKeyRouting.parse(impossibleId));
  }

  @Test
  void scopeAndCanonicalizationBoundariesCannotAliasTenantsOrSilentlyNormalizeDni() {
    for (String invalid : List.of("", " ", " tenant", "tenant ", "tenant\n", "t".repeat(129))) {
      assertThrows(IllegalArgumentException.class, () -> new FieldScope(invalid, "domain"));
      assertThrows(IllegalArgumentException.class, () -> new FieldScope("tenant", invalid));
    }
    assertThrows(IllegalArgumentException.class, () -> new FieldScope(null, "domain"));
    assertEquals("t".repeat(128), new FieldScope("t".repeat(128), "domain").tenant());
    assertThrows(CryptoException.class, () -> new IndexScope(null, "DNI", "t", "d", "p", 1));
    assertThrows(CryptoException.class, () -> new IndexScope(" ", "DNI", "t", "d", "p", 1));
    assertThrows(CryptoException.class, () -> new IndexScope("s", "DNI", "t", "d", "p", 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FieldProtectionPolicy("s", "DNI", "p", 0, value -> value));
    for (String invalid : List.of("1234567", "123456789", " 12345678", "abcdefgh"))
      assertThrows(
          IllegalArgumentException.class,
          () -> FieldProtectionPolicy.dniPe("search").canonicalizer().apply(invalid));
    assertThrows(IllegalArgumentException.class, () -> SecureRandoms.drbg(null));
    assertThrows(IllegalArgumentException.class, () -> SecureRandoms.drbg("wrong purpose"));
  }
}
