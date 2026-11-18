package pe.axiz.core.tokenization.vault;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.BlindIndex;
import pe.axiz.core.crypto.DataKeyProvider;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import pe.axiz.core.crypto.KeyringDataKeyProvider;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenType;

/** Real crypto and maintenance protocol over a test-only conditional storage model. */
class VaultRotatorProtocolTest {
  private static final TokenDomain DOMAIN = new TokenDomain("payments");
  private static final VaultScope SCOPE =
      new VaultScope(new TokenType("DNI"), "tenant", DOMAIN, "dni.pe", 1);
  private static final TokenCaller ADMIN =
      new TokenCaller("operator", "job", "tenant", Set.of("SCOPE_tokens:rotate"), Set.of(DOMAIN));
  private static final byte[] VALUE = "00883344".getBytes(StandardCharsets.UTF_8);

  private static final class Store extends TestVault implements TokenVaultMaintenance {
    WrappingKeyState wrapping = new WrappingKeyState("main", 1, "old", List.of("old"));
    boolean initialized;
    boolean wrappingInitialized;
    boolean loseMigrationAck;
    @Nullable MigrationOutcome force;
    @Nullable String repeatCursor;
    int changes;

    @Override
    public WrappingKeyState wrappingState(String keyset) {
      return wrapping;
    }

    @Override
    public boolean initializeWrapping(WrappingKeyState initial) {
      if (wrappingInitialized) return false;
      wrappingInitialized = true;
      wrapping = initial;
      return true;
    }

    @Override
    public boolean initialize(FingerprintKeyState initial) {
      if (initialized) return false;
      initialized = true;
      state = initial;
      return true;
    }

    @Override
    public boolean transitionWrapping(WrappingKeyTransition transition) {
      if (!wrapping.equals(transition.expected())) return false;
      wrapping = transition.next();
      return true;
    }

    @Override
    public boolean transition(VaultKeyTransition transition) {
      if (!state.equals(transition.expected())) return false;
      state = transition.next();
      return true;
    }

    @Override
    public VaultScanPage scan(String keyset, @Nullable String cursor, int limit) {
      return new VaultScanPage(new ArrayList<>(records.values()), repeatCursor);
    }

    @Override
    public VaultReferencePage references(
        String keyset, String key, @Nullable String cursor, int limit) {
      return new VaultReferencePage(
          (int) references.keySet().stream().filter(ref -> ref.hash().keyId().equals(key)).count(),
          repeatCursor);
    }

    @Override
    public MigrationOutcome replace(
        VaultRecord expected, VaultRecord next, FingerprintKeyState control) {
      if (force != null) return force;
      if (!state.equals(control)) return MigrationOutcome.STALE_KEY_STATE;
      if (!expected.equals(records.get(expected.token()))) return MigrationOutcome.CHANGED;
      records.put(next.token(), next);
      references.entrySet().removeIf(entry -> entry.getValue().equals(next.token()));
      for (var hash : next.fingerprints())
        references.put(new Reference(next.scope(), hash), next.token());
      changes++;
      if (loseMigrationAck) {
        loseMigrationAck = false;
        throw new VaultUncertainException();
      }
      return MigrationOutcome.MIGRATED;
    }
  }

  private static DataKeyProvider provider(int value) {
    return new DataKeyProvider() {
      private byte[] plaintext() {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
      }

      @Override
      public DataKey generate(String context) {
        return new DataKey(plaintext(), new byte[] {(byte) value});
      }

      @Override
      public byte[] decrypt(byte[] encrypted, String context) {
        return plaintext();
      }
    };
  }

  private static final class Fixture implements AutoCloseable {
    final Store store = new Store();
    final List<VaultMaintenanceEvent> events = new ArrayList<>();
    final List<UncertainVaultMaintenance> uncertain = new ArrayList<>();
    final BlindIndex index =
        new BlindIndex(Map.of("old", new byte[32], "new", new byte[64]), "old");
    final Map<String, DataKeyProvider> providers = Map.of("old", provider(1), "new", provider(2));
    final KeyringDataKeyProvider oldKeys = new KeyringDataKeyProvider(providers, "old");
    final KeyringDataKeyProvider newKeys = new KeyringDataKeyProvider(providers, "new");
    final EnvelopeEncryptor oldCipher =
        new EnvelopeEncryptor(oldKeys, Duration.ofMinutes(1), 1, Clock.systemUTC());
    final EnvelopeEncryptor newCipher =
        new EnvelopeEncryptor(newKeys, Duration.ofMinutes(1), 1, Clock.systemUTC());
    final VaultRotator job =
        VaultRotator.builder()
            .vault(store)
            .indexes(index)
            .source(oldCipher)
            .access((caller, action, keyset, scope) -> {})
            .audit(events::add)
            .reconciliation(uncertain::add)
            .alarm(operation -> {})
            .clock(Clock.systemUTC())
            .ids(UUID::randomUUID)
            .authority("SCOPE_tokens:rotate")
            .maxProofPages(4)
            .proofTimeout(Duration.ofSeconds(10))
            .build();

    VaultRecord seed() {
      var token = Token.fromEntropy(new byte[16]);
      var row =
          VaultRecord.builder()
              .token(token)
              .scope(SCOPE)
              .keysetId("main")
              .fingerprints(List.of(index.index("00883344", SCOPE.indexScope(), "old")))
              .revision(1)
              .ciphertext(
                  oldCipher.encrypt(VALUE, VaultPolicy.ENCRYPTION_CONTEXT, SCOPE.binding(token)))
              .createdAt(Instant.EPOCH)
              .expiresAt(Instant.EPOCH.plusSeconds(86400))
              .metadata(Map.of("label", "synthetic"))
              .build();
      store.putIfAbsent(row, store.state, List.of());
      return row;
    }

    @Override
    public void close() {
      oldCipher.close();
      newCipher.close();
      index.close();
    }
  }

  @Test
  void hmacRotationPreservesTokenAndCiphertextAndProvesBothRowsAndReferencesBeforeRetirement() {
    try (var f = new Fixture()) {
      assertTrue(f.job.initialize(ADMIN, "main", "old"));
      assertFalse(f.job.initialize(ADMIN, "main", "old"));
      var row = f.seed();
      assertThrows(AxizException.class, () -> f.job.activate(ADMIN, f.store.state, "old"));
      assertTrue(f.job.activate(ADMIN, f.store.state, "new"));
      var dual = f.store.state;
      assertEquals(List.of("new", "old"), dual.readKeyIds());
      assertThrows(AxizException.class, () -> f.job.preparePrune(ADMIN, dual, "old"));
      var migrated = f.job.reindex(ADMIN, dual, null, 100);
      assertEquals(new VaultMaintenanceBatch(1, 1, 0, null), migrated);
      var next = f.store.records.get(row.token());
      assertEquals(2, next.fingerprints().size());
      assertArrayEquals(row.ciphertext(), next.ciphertext());
      assertEquals(row.scope(), next.scope());
      assertEquals(row.metadata(), next.metadata());
      assertEquals(row.expiresAt(), next.expiresAt());
      assertEquals(2, next.revision());
      assertEquals(0, f.job.reindex(ADMIN, dual, null, 100).migrated());
      assertThrows(AxizException.class, () -> f.job.retire(ADMIN, dual, "old"));
      var plan = f.job.preparePrune(ADMIN, dual, "old");
      assertEquals(1, f.job.prune(ADMIN, plan, null, 100).migrated());
      assertEquals(0, f.job.prune(ADMIN, plan, null, 100).migrated());
      assertTrue(f.job.retire(ADMIN, dual, "old"));
      assertEquals(List.of("new"), f.store.state.readKeyIds());
      assertEquals(3, f.store.state.version());
      assertEquals(1, f.store.references.size());
      assertEquals(2, f.store.changes);
      assertTrue(f.uncertain.isEmpty());
      assertFalse(f.events.isEmpty());
    }
  }

  @Test
  void wrappingRotationIsIndependentAndRetiresOnlyAfterAuthenticatedMigration() {
    try (var f = new Fixture()) {
      var row = f.seed();
      assertTrue(f.job.initializeWrapping(ADMIN, "main", f.oldKeys));
      assertFalse(f.job.initializeWrapping(ADMIN, "main", f.oldKeys));
      var first = f.store.wrapping;
      assertThrows(AxizException.class, () -> f.job.activateWrapping(ADMIN, first, f.oldKeys));
      var unreadable = new KeyringDataKeyProvider(Map.of("new", provider(2)), "new");
      assertThrows(AxizException.class, () -> f.job.activateWrapping(ADMIN, first, unreadable));
      assertTrue(f.job.activateWrapping(ADMIN, first, f.newKeys));
      var dual = f.store.wrapping;
      assertEquals(List.of("new", "old"), dual.readKeyIds());
      assertThrows(AxizException.class, () -> f.job.retireWrapping(ADMIN, dual, "new"));
      assertThrows(AxizException.class, () -> f.job.retireWrapping(ADMIN, dual, "missing"));
      assertThrows(AxizException.class, () -> f.job.retireWrapping(ADMIN, dual, "old"));
      assertEquals(1, f.job.reencrypt(ADMIN, f.store.state, null, 100, f.newCipher).migrated());
      var next = f.store.records.get(row.token());
      assertNotEquals(Arrays.toString(row.ciphertext()), Arrays.toString(next.ciphertext()));
      assertEquals("new", KeyringDataKeyProvider.envelopeKeyId(next.ciphertext()));
      assertArrayEquals(
          VALUE,
          f.oldCipher.decrypt(
              next.ciphertext(), VaultPolicy.ENCRYPTION_CONTEXT, SCOPE.binding(next.token())));
      assertEquals(row.fingerprints(), next.fingerprints());
      assertEquals(row.expiresAt(), next.expiresAt());
      assertTrue(f.job.retireWrapping(ADMIN, dual, "old"));
      assertEquals(List.of("new"), f.store.wrapping.readKeyIds());
      assertEquals("old", f.store.state.writeKeyId());
    }
  }

  @Test
  void conflictingWritesCorruptionAndUnknownCommitsNeverCountAsSuccessfulMigrations() {
    for (var outcome :
        List.of(
            MigrationOutcome.CHANGED,
            MigrationOutcome.STALE_KEY_STATE,
            MigrationOutcome.INTEGRITY_FAILURE)) {
      try (var f = new Fixture()) {
        var row = f.seed();
        assertTrue(f.job.activate(ADMIN, f.store.state, "new"));
        f.store.force = outcome;
        if (outcome == MigrationOutcome.CHANGED) {
          assertEquals(
              new VaultMaintenanceBatch(1, 0, 1, null),
              f.job.reindex(ADMIN, f.store.state, null, 100));
        } else {
          assertThrows(AxizException.class, () -> f.job.reindex(ADMIN, f.store.state, null, 100));
        }
        assertEquals(row, f.store.records.get(row.token()));
      }
    }
    try (var f = new Fixture()) {
      f.seed();
      assertTrue(f.job.activate(ADMIN, f.store.state, "new"));
      f.store.loseMigrationAck = true;
      assertThrows(AxizException.class, () -> f.job.reindex(ADMIN, f.store.state, null, 100));
      assertEquals(1, f.store.changes);
      assertFalse(f.uncertain.isEmpty());
    }
  }

  @Test
  void repeatedProofCursorAndStaleWrappingControlCannotRetireAKey() {
    try (var f = new Fixture()) {
      assertTrue(f.job.activate(ADMIN, f.store.state, "new"));
      var state = f.store.state;
      f.store.repeatCursor = "same-cursor";
      assertThrows(AxizException.class, () -> f.job.preparePrune(ADMIN, state, "old"));
      assertThrows(AxizException.class, () -> f.job.retire(ADMIN, state, "old"));
      assertEquals(state, f.store.state);
      var stale = new WrappingKeyState("main", 2, "new", List.of("new", "old"));
      assertThrows(AxizException.class, () -> f.job.retireWrapping(ADMIN, stale, "old"));
    }
  }
}
