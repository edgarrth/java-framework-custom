package pe.axiz.core.tokenization.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.BlindIndex;
import pe.axiz.core.crypto.DataKeyProvider;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.RateLimitedException;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenType;

class VaultMaintenanceBoundsTest {
  private static final TokenDomain DOMAIN = new TokenDomain("payments");
  private static final TokenCaller ADMIN =
      new TokenCaller("migration", "job", "tenant", Set.of("SCOPE_tokens:rotate"), Set.of(DOMAIN));
  private static final VaultScope SCOPE =
      new VaultScope(new TokenType("DNI"), "tenant", DOMAIN, "dni.pe", 1);
  private static final FingerprintKeyState DUAL =
      new FingerprintKeyState("main", 2, "new", List.of("new", "old"));

  private static final class Store extends TestVault implements TokenVaultMaintenance {
    List<VaultRecord> page = List.of();
    boolean endless;
    boolean orphan;
    int scans;
    int transitions;
    int replacements;

    Store() {
      state = DUAL;
    }

    @Override
    public WrappingKeyState wrappingState(String keyset) {
      throw new AssertionError("Not used");
    }

    @Override
    public boolean initializeWrapping(WrappingKeyState state) {
      throw new AssertionError("Not used");
    }

    @Override
    public boolean transitionWrapping(WrappingKeyTransition transition) {
      throw new AssertionError("Not used");
    }

    @Override
    public boolean initialize(FingerprintKeyState initial) {
      throw new AssertionError("Not used");
    }

    @Override
    public VaultScanPage scan(String keyset, @Nullable String cursor, int limit) {
      scans++;
      return new VaultScanPage(page, endless ? "cursor-" + scans : null);
    }

    @Override
    public VaultReferencePage references(
        String keyset, String key, @Nullable String cursor, int limit) {
      return new VaultReferencePage(orphan ? 1 : 0, null);
    }

    @Override
    public boolean transition(VaultKeyTransition transition) {
      transitions++;
      return true;
    }

    @Override
    public MigrationOutcome replace(
        VaultRecord expected, VaultRecord next, FingerprintKeyState state) {
      replacements++;
      return MigrationOutcome.MIGRATED;
    }
  }

  private static EnvelopeEncryptor noDecryption() {
    return new EnvelopeEncryptor(
        new DataKeyProvider() {
          @Override
          public DataKey generate(String context) {
            throw new AssertionError("Unexpected key generation");
          }

          @Override
          public byte[] decrypt(byte[] wrapped, String context) {
            throw new AssertionError("Unexpected decryption");
          }
        },
        Duration.ofMinutes(1),
        100,
        Clock.systemUTC());
  }

  private static VaultRecord row(BlindIndex index, String key) {
    return VaultRecord.builder()
        .token(Token.fromEntropy(new byte[16]))
        .scope(SCOPE)
        .keysetId("main")
        .fingerprints(List.of(index.index("00883344", SCOPE.indexScope(), key)))
        .revision(1)
        .ciphertext(new byte[40])
        .createdAt(Instant.EPOCH)
        .expiresAt(Instant.EPOCH.plusSeconds(30))
        .metadata(Map.of())
        .build();
  }

  private static VaultRotator job(
      Store store,
      BlindIndex index,
      EnvelopeEncryptor encryption,
      List<VaultMaintenanceEvent> events) {
    return VaultRotator.builder()
        .vault(store)
        .indexes(index)
        .source(encryption)
        .access((caller, action, keyset, scope) -> {})
        .audit(events::add)
        .reconciliation(
            operation -> {
              throw new AssertionError("Unexpected uncertainty");
            })
        .alarm(
            operation -> {
              throw new AssertionError("Unexpected alarm");
            })
        .clock(Clock.systemUTC())
        .ids(UUID::randomUUID)
        .authority("SCOPE_tokens:rotate")
        .maxProofPages(2)
        .proofTimeout(Duration.ofSeconds(10))
        .build();
  }

  @Test
  void sanitizedBudgetFailurePreservesOnlyTheBoundedDelay() {
    var original = TokenErrors.limited(Duration.ofMillis(1501));
    original.addSuppressed(new IllegalStateException("synthetic private provider detail"));
    var safe = (RateLimitedException) VaultPolicy.safeFailure(original);
    assertEquals(2, safe.retryAfterSeconds());
    assertEquals("AXZ-TOKEN-0429", safe.code().value());
    assertEquals(0, safe.getSuppressed().length);
    assertEquals(null, safe.getCause());
  }

  @Test
  void anIncompleteOrUnboundedProofCannotIssueAPrunePlanOrRetireAKey() {
    var store = new Store();
    var events = new ArrayList<VaultMaintenanceEvent>();
    try (var index = new BlindIndex(Map.of("old", new byte[32], "new", new byte[64]), "new");
        var encryption = noDecryption()) {
      var job = job(store, index, encryption, events);
      store.endless = true;
      assertThrows(AxizException.class, () -> job.preparePrune(ADMIN, DUAL, "old"));
      assertEquals(2, store.scans);
      assertThrows(AxizException.class, () -> job.retire(ADMIN, DUAL, "old"));
      assertEquals(0, store.transitions);
      store.endless = false;
      store.page = List.of(row(index, "old"));
      assertThrows(AxizException.class, () -> job.preparePrune(ADMIN, DUAL, "old"));
      assertThrows(AxizException.class, () -> job.retire(ADMIN, DUAL, "old"));
      store.page = List.of(row(index, "new"));
      var plan = job.preparePrune(ADMIN, DUAL, "old");
      store.orphan = true;
      assertThrows(AxizException.class, () -> job.retire(ADMIN, DUAL, "old"));
      assertEquals(0, store.transitions);
      store.state = new FingerprintKeyState("main", 3, "new", List.of("new"));
      assertThrows(AxizException.class, () -> job.prune(ADMIN, plan, null, 100));
      assertEquals(0, store.replacements);
    }
  }

  @Test
  void interruptionAndMissingKeyMaterialFailBeforeAnyScan() {
    var store = new Store();
    var events = new ArrayList<VaultMaintenanceEvent>();
    try (var index = new BlindIndex(Map.of("new", new byte[64]), "new");
        var encryption = noDecryption()) {
      var job = job(store, index, encryption, events);
      assertThrows(AxizException.class, () -> job.retire(ADMIN, DUAL, "old"));
      assertEquals(0, store.scans);
      assertEquals(0, store.transitions);
    }
    try (var index = new BlindIndex(Map.of("old", new byte[32], "new", new byte[64]), "new");
        var encryption = noDecryption()) {
      var job = job(store, index, encryption, events);
      Thread.currentThread().interrupt();
      try {
        assertThrows(AxizException.class, () -> job.preparePrune(ADMIN, DUAL, "old"));
      } finally {
        Thread.interrupted();
      }
      assertEquals(0, store.scans);
      assertEquals(0, store.transitions);
    }
  }
}
