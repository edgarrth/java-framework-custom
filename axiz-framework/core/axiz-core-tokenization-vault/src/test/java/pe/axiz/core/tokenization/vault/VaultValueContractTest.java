package pe.axiz.core.tokenization.vault;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.BlindIndex;
import pe.axiz.core.crypto.KeyedHash;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenType;

class VaultValueContractTest {
  private static final VaultScope SCOPE =
      new VaultScope(new TokenType("DNI"), "tenant", new TokenDomain("payments"), "dni.pe", 1);
  private static final Token TOKEN = Token.fromEntropy(new byte[16]);

  @Test
  void recordsOwnCiphertextIndexesAndMetadataAndAcceptExactStorageBounds() {
    try (var indexes = new BlindIndex(Map.of("one", new byte[32]), "one")) {
      KeyedHash hash = indexes.index("00883344", SCOPE.indexScope());
      byte[] input = new byte[40];
      Arrays.fill(input, (byte) 7);
      Map<String, String> metadata = new HashMap<>(Map.of("label", "synthetic"));
      List<KeyedHash> fingerprints = new ArrayList<>(List.of(hash));
      VaultRecord row = record(fingerprints).ciphertext(input).metadata(metadata).build();
      Arrays.fill(input, (byte) 0);
      metadata.clear();
      fingerprints.clear();
      assertEquals(7, row.ciphertext()[0]);
      byte[] returned = row.ciphertext();
      Arrays.fill(returned, (byte) 0);
      assertEquals(7, row.ciphertext()[0]);
      assertEquals(Map.of("label", "synthetic"), row.metadata());
      assertEquals(List.of(hash), row.fingerprints());
      assertThrows(UnsupportedOperationException.class, () -> row.metadata().clear());
      assertThrows(UnsupportedOperationException.class, () -> row.fingerprints().clear());
      assertEquals("VaultRecord[" + TOKEN.masked() + ", revision=1]", row.toString());
      assertFalse(row.toString().contains(TOKEN.reveal()));
      assertEquals(
          100_000, record(List.of(hash)).ciphertext(new byte[100_000]).build().ciphertext().length);
      assertThrows(
          AxizException.class, () -> record(List.of(hash)).ciphertext(new byte[39]).build());
      assertThrows(
          AxizException.class, () -> record(List.of(hash)).ciphertext(new byte[100_001]).build());
      assertThrows(
          AxizException.class, () -> record(List.of(hash)).expiresAt(Instant.EPOCH).build());
      assertThrows(AxizException.class, () -> record(List.of(hash)).revision(0).build());
      assertThrows(AxizException.class, () -> record(List.of(hash, hash)).build());
      var keys = new HashMap<String, byte[]>();
      for (int i = 0; i < 16; i++) keys.put("key" + i, new byte[32]);
      try (var many = new BlindIndex(keys, "key0")) {
        var hashes = new ArrayList<KeyedHash>();
        for (String id : keys.keySet()) hashes.add(many.index("00883344", SCOPE.indexScope(), id));
        assertEquals(16, record(hashes).build().fingerprints().size());
        hashes.add(hash);
        assertThrows(AxizException.class, () -> record(hashes).build());
      }
    }
  }

  private static VaultRecord.Builder record(List<KeyedHash> fingerprints) {
    return VaultRecord.builder()
        .token(TOKEN)
        .scope(SCOPE)
        .keysetId("main")
        .fingerprints(fingerprints)
        .revision(1)
        .ciphertext(new byte[40])
        .createdAt(Instant.EPOCH)
        .expiresAt(Instant.EPOCH.plusSeconds(1))
        .metadata(Map.of());
  }

  @Test
  void identityAndKeyControlsRejectUnboundedOrInvalidCollectionsAndOwnTheirSnapshots() {
    var authorities = new HashSet<String>();
    var domains = new HashSet<TokenDomain>();
    for (int i = 0; i < 128; i++) authorities.add("SCOPE_" + i);
    for (int i = 0; i < 64; i++) domains.add(new TokenDomain("domain-" + i));
    var caller = new TokenCaller("subject", "client", "tenant", authorities, domains);
    assertEquals(128, caller.authorities().size());
    assertEquals(64, caller.domains().size());
    authorities.add("extra");
    domains.add(new TokenDomain("extra"));
    assertThrows(
        AxizException.class,
        () ->
            new TokenCaller(
                "subject", "client", "tenant", authorities, Set.of(new TokenDomain("one"))));
    assertThrows(
        AxizException.class,
        () -> new TokenCaller("subject", "client", "tenant", Set.of("scope"), domains));
    assertThrows(
        AxizException.class,
        () ->
            new TokenCaller(
                "subject",
                "client",
                "tenant",
                Set.of("bad authority"),
                Set.of(new TokenDomain("one"))));
    authorities.clear();
    domains.clear();
    assertEquals(128, caller.authorities().size());
    assertEquals(64, caller.domains().size());
    assertEquals("TokenCaller[verified]", caller.toString());
    var keys = new ArrayList<String>();
    for (int i = 0; i < 16; i++) keys.add("key" + i);
    var state = new FingerprintKeyState("main", 1, "key0", keys);
    var wrapping = new WrappingKeyState("main", 1, "key0", keys);
    keys.add("key16");
    assertThrows(AxizException.class, () -> new FingerprintKeyState("main", 1, "key0", keys));
    assertThrows(
        AxizException.class,
        () -> new FingerprintKeyState("main", 1, "key0", List.of("key0", "bad key")));
    assertThrows(
        AxizException.class,
        () -> new FingerprintKeyState("main", 1, "key0", List.of("key0", "key0")));
    keys.clear();
    assertEquals(16, state.readKeyIds().size());
    assertEquals(16, wrapping.readKeyIds().size());
    assertThrows(UnsupportedOperationException.class, () -> wrapping.readKeyIds().clear());
  }

  @Test
  void storageScopeIsVersionedLengthFramedAndAllProgressDiagnosticsExcludeTheCursor() {
    ByteBuffer encoded = ByteBuffer.wrap(Base64.getUrlDecoder().decode(SCOPE.encoded()));
    assertEquals(1, encoded.get());
    assertEquals(1, encoded.getInt());
    for (String part : List.of("DNI", "tenant", "payments", "dni.pe")) {
      byte[] actual = new byte[encoded.getInt()];
      encoded.get(actual);
      assertArrayEquals(part.getBytes(StandardCharsets.US_ASCII), actual);
    }
    assertFalse(encoded.hasRemaining());
    assertEquals("VaultScope[DNI, dni.pe, 1]", SCOPE.toString());
    assertEquals(
        "VaultScanPage[count=0, more=false]", new VaultScanPage(List.of(), null).toString());
    assertEquals(
        "VaultScanPage[count=0, more=true]", new VaultScanPage(List.of(), "private").toString());
    assertEquals(
        "VaultMaintenanceBatch[inspected=100, migrated=50, changed=50]",
        new VaultMaintenanceBatch(100, 50, 50, "private").toString());
    assertEquals(
        "VaultReferencePage[count=100]", new VaultReferencePage(100, "private").toString());
    assertEquals(
        "VaultRetentionBatch[inspected=100, deleted=25, absent=25, changed=25, retained=25]",
        new VaultRetentionBatch(100, 25, 25, 25, 25, "private").toString());
    assertEquals(0, new VaultRetentionBatch(0, 0, 0, 0, 0, "x".repeat(1024)).inspected());
    assertThrows(
        IllegalArgumentException.class, () -> new VaultRetentionBatch(101, 101, 0, 0, 0, null));
    try (var indexes = new BlindIndex(Map.of("one", new byte[32]), "one")) {
      var row = record(List.of(indexes.index("00883344", SCOPE.indexScope()))).build();
      var page = new ArrayList<VaultRecord>();
      for (int i = 0; i < 100; i++) page.add(row);
      assertEquals(100, new VaultScanPage(page, null).records().size());
      page.add(row);
      assertThrows(IllegalArgumentException.class, () -> new VaultScanPage(page, null));
    }
  }

  @Test
  void secureOperationIdsHaveIndependentEntropyAndCanonicalUuidV4Bits() {
    var generator = new SecureTokenGenerator();
    var first = generator.operationId();
    var next = generator.operationId();
    assertEquals(4, first.version());
    assertEquals(2, first.variant());
    assertNotEquals(first, next);
    assertTrue(first.toString().length() == 36);
  }
}
