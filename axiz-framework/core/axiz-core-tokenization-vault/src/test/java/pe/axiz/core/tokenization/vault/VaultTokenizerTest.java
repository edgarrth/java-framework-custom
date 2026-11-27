package pe.axiz.core.tokenization.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.BlindIndex;
import pe.axiz.core.crypto.DataKeyProvider;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.TextTokenProfiles;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenOperation;
import pe.axiz.core.tokenization.TokenProfile;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.TokenizationResult;

class VaultTokenizerTest {
  static final TokenType DNI = new TokenType("DNI");
  static final TokenType TEXT = new TokenType("TEXT");
  static final TokenDomain DOMAIN = new TokenDomain("payments");
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"), ZoneOffset.UTC);
  final TestVault store = new TestVault();
  final List<TokenAuditEntry> events = new CopyOnWriteArrayList<>();
  final List<UncertainTokenOperation> uncertain = new CopyOnWriteArrayList<>();
  final AtomicInteger admitted = new AtomicInteger();
  final AtomicInteger released = new AtomicInteger();
  final AtomicInteger alarms = new AtomicInteger();
  final BlindIndex index = new BlindIndex(Map.of("old", new byte[32], "new", filled(32, 3)), "old");
  final EnvelopeEncryptor encryption =
      new EnvelopeEncryptor(
          new DataKeyProvider() {
            @Override
            public DataKey generate(String context) {
              return new DataKey(filled(32, 1), new byte[] {1});
            }

            @Override
            public byte[] decrypt(byte[] encrypted, String context) {
              return filled(32, 1);
            }
          },
          Duration.ofMinutes(1),
          10000,
          CLOCK);
  boolean auditDown;
  boolean logDown;
  boolean limit;
  boolean limiterDown;
  boolean releaseDown;
  final TokenBudget budget =
      new TokenBudget() {
        @Override
        public Permit acquire(TokenCaller caller, TokenOperation operation, VaultScope scope) {
          admitted.incrementAndGet();
          if (limiterDown) throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
          if (limit) throw TokenErrors.failure(TokenErrors.LIMITED);
          return () -> {
            released.incrementAndGet();
            if (releaseDown) throw new IllegalStateException("synthetic");
          };
        }

        @Override
        public void beforeWrite(TokenCaller caller, VaultScope scope) {}

        @Override
        public boolean distributed() {
          return false;
        }
      };
  final VaultPolicy policy =
      new VaultPolicy(
          List.of(
              policy(DNI, TextTokenProfiles.dniPe()), policy(TEXT, TextTokenProfiles.identity())),
          (caller, operation, type, domain, purpose) -> {},
          index,
          "SCOPE_tokens:",
          CLOCK);
  final VaultTokenizer engine = engine(policy);

  VaultTokenizer engine(VaultPolicy configuration) {
    return VaultTokenizer.builder()
        .policy(configuration)
        .vault(store)
        .encryption(encryption)
        .tokens(new SecureTokenGenerator())
        .budget(budget)
        .audit(
            entry -> {
              if (auditDown) throw new IllegalStateException("synthetic audit unavailable");
              events.add(entry);
            })
        .reconciliation(
            entry -> {
              if (logDown) throw new IllegalStateException("synthetic log unavailable");
              uncertain.add(entry);
            })
        .alarm(entry -> alarms.incrementAndGet())
        .operationIds(UUID::randomUUID)
        .build();
  }

  static byte[] filled(int size, int value) {
    byte[] result = new byte[size];
    Arrays.fill(result, (byte) value);
    return result;
  }

  static TokenTypePolicy policy(TokenType type, TokenProfile profile) {
    return TokenTypePolicy.builder()
        .type(type)
        .profile(profile)
        .keysetId("main")
        .domains(Set.of(DOMAIN))
        .purposes(Set.of("customer-support"))
        .retention(Duration.ofDays(30))
        .metadataKeys(Set.of())
        .panException(null)
        .build();
  }

  static TokenCaller caller(String tenant) {
    return new TokenCaller(
        "subject",
        "client",
        tenant,
        Set.of(
            "SCOPE_tokens:tokenize",
            "SCOPE_tokens:lookup",
            "SCOPE_tokens:describe",
            "SCOPE_tokens:detokenize",
            "SCOPE_tokens:delete"),
        Set.of(DOMAIN));
  }

  TokenizationResult tokenize(String raw, TokenType type, String tenant) {
    try (var value = SensitiveValue.of(raw)) {
      return engine.tokenize(caller(tenant), type, value, DOMAIN, UUID.randomUUID().toString());
    }
  }

  static void code(String expected, Runnable action) {
    assertEquals(expected, assertThrows(AxizException.class, action::run).code().value());
  }

  @AfterEach
  void close() {
    encryption.close();
    index.close();
  }

  @Test
  void deduplicatesAcross100ConcurrentRequestsAndScopesDoNotCollide() throws Exception {
    List<Future<TokenizationResult>> work = new ArrayList<>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 100; i++)
        work.add(executor.submit(() -> tokenize("00883344", DNI, "one")));
      Set<Token> tokens = new HashSet<>();
      int created = 0;
      for (var future : work) {
        var result = future.get();
        tokens.add(result.token());
        if (result.created()) created++;
      }
      assertEquals(1, tokens.size());
      assertEquals(1, created);
      assertEquals(1, store.records.size());
      assertEquals(1, store.references.size());
      Token otherTenant = tokenize("00883344", DNI, "two").token();
      Token otherType = tokenize("00883344", TEXT, "one").token();
      assertFalse(tokens.contains(otherTenant));
      assertFalse(tokens.contains(otherType));
    }
    assertEquals(admitted.get(), released.get());
    assertEquals(102, events.size());
    assertTrue(events.stream().noneMatch(entry -> entry.toString().contains("00883344")));
  }

  @Test
  void lookupNeverWritesAndRevealRequiresOwnAuthorityScopeAndConfirmedAudit() {
    Token token = tokenize("00883344", DNI, "one").token();
    int writes = store.writes.get();
    try (var value = SensitiveValue.of("00883344");
        var absent = SensitiveValue.of("99999999")) {
      assertEquals(
          token,
          engine.lookup(caller("one"), DNI, value, DOMAIN, "customer-support").orElseThrow());
      assertTrue(engine.lookup(caller("one"), DNI, absent, DOMAIN, "customer-support").isEmpty());
      assertEquals(writes, store.writes.get());
      var describeOnly =
          new TokenCaller(
              "subject", "client", "one", Set.of("SCOPE_tokens:describe"), Set.of(DOMAIN));
      code(
          "AXZ-TOKEN-0403",
          () -> engine.lookup(describeOnly, DNI, value, DOMAIN, "customer-support"));
      code(
          "AXZ-TOKEN-0403",
          () -> engine.detokenize(describeOnly, DNI, token, DOMAIN, "customer-support"));
      code(
          "AXZ-TOKEN-0403",
          () -> engine.detokenize(caller("one"), DNI, token, DOMAIN, "arbitrary-client-purpose"));
      code(
          "AXZ-TOKEN-0404",
          () -> engine.detokenize(caller("two"), DNI, token, DOMAIN, "customer-support"));
    }
    try (var value = engine.detokenize(caller("one"), DNI, token, DOMAIN, "customer-support")) {
      assertEquals("00883344", value.reveal());
    }
    auditDown = true;
    code(
        "AXZ-TOKEN-0503",
        () -> engine.detokenize(caller("one"), DNI, token, DOMAIN, "customer-support"));
    code(TokenErrors.UNCERTAIN.code().value(), () -> tokenize("00112233", DNI, "one"));
    assertEquals(
        UncertainTokenOperation.Reason.COMMITTED_AUDIT_UNCONFIRMED, uncertain.getLast().reason());
    assertEquals(
        2, store.records.size()); // Una mutación confirmada no se revierte como compensación.
    auditDown = false;
    assertFalse(tokenize("00112233", DNI, "one").created());
  }

  @Test
  void confirmedDeleteWithMissingAuditIsReconciledWithoutRepeatingTheMutation() {
    var token = tokenize("00883344", DNI, "one").token();
    auditDown = true;
    code(
        TokenErrors.UNCERTAIN.code().value(),
        () -> engine.delete(caller("one"), DNI, token, DOMAIN));
    assertTrue(store.records.isEmpty());
    assertEquals(UncertainTokenOperation.Phase.DELETE, uncertain.getLast().phase());
    assertEquals(
        UncertainTokenOperation.Reason.COMMITTED_AUDIT_UNCONFIRMED, uncertain.getLast().reason());
    assertEquals(token, uncertain.getLast().token());
  }

  @Test
  void denyAndBudgetFailureHappenBeforeIndexOrStorageAndAreAudited() {
    limit = true;
    code("AXZ-TOKEN-0429", () -> tokenize("00883344", DNI, "one"));
    assertEquals(0, store.reads.get());
    assertEquals(0, store.writes.get());
    limit = false;
    limiterDown = true;
    code("AXZ-TOKEN-0503", () -> tokenize("00883344", DNI, "one"));
    assertEquals(0, store.reads.get());
    assertEquals(
        List.of(TokenAuditEntry.Result.RATE_LIMITED, TokenAuditEntry.Result.UNAVAILABLE),
        events.stream().map(TokenAuditEntry::result).toList());
  }

  @Test
  void refusesDisguisedPanInOriginalAndCanonicalValuesBeforePersistence() {
    for (String pan :
        List.of("4111111111111111", "4111-1111-1111-1111", "identifier 4111 1111 1111 1111 end"))
      code("AXZ-TOKEN-0400", () -> tokenize(pan, TEXT, "one"));
    TokenProfile normalizing =
        new TokenProfile() {
          @Override
          public String id() {
            return "test.canonical";
          }

          @Override
          public int version() {
            return 1;
          }

          @Override
          public SensitiveValue canonicalize(SensitiveValue input) {
            return SensitiveValue.of("4111111111111111");
          }
        };
    var configured =
        engine(
            new VaultPolicy(
                List.of(policy(TEXT, normalizing)),
                (c, o, t, d, p) -> {},
                index,
                "SCOPE_tokens:",
                CLOCK));
    try (var input = SensitiveValue.of("synthetic-code")) {
      code(
          "AXZ-TOKEN-0400",
          () ->
              configured.tokenize(
                  caller("one"), TEXT, input, DOMAIN, UUID.randomUUID().toString()));
    }
    assertEquals(0, store.reads.get());
    assertEquals(0, store.writes.get());
  }

  @Test
  void unknownCommitIsReconciledWithoutAutomaticRetryAndThenDeduplicated() {
    store.lostAck = true;
    code("AXZ-TOKEN-0504", () -> tokenize("00883344", DNI, "one"));
    assertEquals(1, store.writes.get());
    assertEquals(1, uncertain.size());
    assertEquals(1, store.records.size());
    assertEquals(uncertain.getFirst().token(), tokenize("00883344", DNI, "one").token());
    assertEquals(1, store.writes.get());
    store.lostAck = true;
    logDown = true;
    auditDown = true;
    code("AXZ-TOKEN-0504", () -> tokenize("00112233", DNI, "one"));
    assertEquals(1, alarms.get());
    assertEquals(2, store.writes.get());
  }

  @Test
  void staleKeyStateReloadsButMissingRequiredKeysAndOrphansFailClosed() {
    store.staleOnce = true;
    Token token = tokenize("00883344", DNI, "one").token();
    assertEquals("new", store.records.get(token).fingerprints().getFirst().keyId());
    assertEquals(2, store.writes.get());
    store.state = new FingerprintKeyState("main", 3, "missing", List.of("missing", "new"));
    code("AXZ-TOKEN-0503", () -> tokenize("00112233", DNI, "one"));
    assertEquals(2, store.writes.get());
    store.state = new FingerprintKeyState("main", 4, "new", List.of("new", "old"));
    store.orphan = true;
    code("AXZ-TOKEN-0503", () -> tokenize("00883344", DNI, "one"));
    assertEquals(TokenAuditEntry.Result.INTEGRITY_FAILURE, events.getLast().result());
  }

  @Test
  void tokenCollisionRetriesAreBoundedAndOperationUuidDoesNotPromiseIdempotency() {
    store.constantCollision = true;
    code("AXZ-TOKEN-0503", () -> tokenize("00883344", DNI, "one"));
    assertEquals(3, store.writes.get());
    assertTrue(store.records.isEmpty());
    store.constantCollision = false;
    try (var value = SensitiveValue.of("00883344")) {
      code(
          "AXZ-TOKEN-0400",
          () -> engine.tokenize(caller("one"), DNI, value, DOMAIN, "not-a-random-uuid"));
      String operation = UUID.randomUUID().toString();
      Token token = engine.tokenize(caller("one"), DNI, value, DOMAIN, operation).token();
      assertTrue(engine.delete(caller("one"), DNI, token, DOMAIN));
      assertFalse(engine.delete(caller("one"), DNI, token, DOMAIN));
      assertTrue(store.references.isEmpty());
      assertNotEquals(token, engine.tokenize(caller("one"), DNI, value, DOMAIN, operation).token());
    }
  }

  @Test
  void expiredRecordsAreNotRevealedAndRecreationUsesCoordinatedDeletion() {
    Token token = tokenize("00883344", DNI, "one").token();
    var later =
        engine(
            new VaultPolicy(
                List.of(policy(DNI, TextTokenProfiles.dniPe())),
                (c, o, t, d, p) -> {},
                index,
                "SCOPE_tokens:",
                Clock.offset(CLOCK, Duration.ofDays(31))));
    assertTrue(later.describe(caller("one"), DNI, token, DOMAIN).isEmpty());
    code(
        "AXZ-TOKEN-0404",
        () -> later.detokenize(caller("one"), DNI, token, DOMAIN, "customer-support"));
    try (var input = SensitiveValue.of("00883344")) {
      assertTrue(later.lookup(caller("one"), DNI, input, DOMAIN, "customer-support").isEmpty());
      Token replacement =
          later.tokenize(caller("one"), DNI, input, DOMAIN, UUID.randomUUID().toString()).token();
      assertNotEquals(token, replacement);
      assertEquals(1, store.records.size());
      assertEquals(1, store.references.size());
    }
  }

  @Test
  void bindingRejectsCiphertextSubstitutionAndSnapshotsAreDefensive() {
    Token first = tokenize("00883344", DNI, "one").token();
    Token second = tokenize("00112233", DNI, "one").token();
    VaultRecord original = store.records.get(first);
    byte[] tamper = original.ciphertext();
    tamper[0] ^= 1;
    assertNotEquals(tamper[0], original.ciphertext()[0]);
    VaultRecord wrong =
        VaultRecord.builder()
            .token(first)
            .scope(original.scope())
            .keysetId("main")
            .fingerprints(original.fingerprints())
            .revision(original.revision())
            .ciphertext(store.records.get(second).ciphertext())
            .createdAt(original.createdAt())
            .expiresAt(original.expiresAt())
            .metadata(original.metadata())
            .build();
    store.records.put(first, wrong);
    code(
        "AXZ-TOKEN-0503",
        () -> engine.detokenize(caller("one"), DNI, first, DOMAIN, "customer-support"));
    assertFalse(wrong.toString().contains("00883344"));
  }
}
