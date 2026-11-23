package pe.axiz.core.tokenization.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pe.axiz.core.tokenization.vault.VaultTokenizerTest.CLOCK;
import static pe.axiz.core.tokenization.vault.VaultTokenizerTest.DNI;
import static pe.axiz.core.tokenization.vault.VaultTokenizerTest.DOMAIN;
import static pe.axiz.core.tokenization.vault.VaultTokenizerTest.caller;
import static pe.axiz.core.tokenization.vault.VaultTokenizerTest.filled;
import static pe.axiz.core.tokenization.vault.VaultTokenizerTest.policy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.AsyncDataKeyProvider;
import pe.axiz.core.crypto.AsyncEnvelopeEncryptor;
import pe.axiz.core.crypto.BlindIndex;
import pe.axiz.core.crypto.DataKeyProvider.DataKey;
import pe.axiz.core.crypto.KeyedHash;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.TextTokenProfiles;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenOperation;
import pe.axiz.core.tokenization.TokenProfile;
import pe.axiz.core.tokenization.TokenizationResult;

class AsyncVaultTokenizerTest {
  final SecureTokenGenerator random = new SecureTokenGenerator();
  final TestVault store = new TestVault();
  final List<TokenAuditEntry> events = new CopyOnWriteArrayList<>();
  final List<UncertainTokenOperation> uncertainties = new CopyOnWriteArrayList<>();
  final AtomicInteger released = new AtomicInteger();
  final AtomicInteger writesBudget = new AtomicInteger();
  final AtomicInteger alarms = new AtomicInteger();
  final List<SensitiveValue> canonicalValues = new CopyOnWriteArrayList<>();
  final BlindIndex indexes =
      new BlindIndex(Map.of("old", new byte[32], "new", filled(32, 3)), "old");
  final AsyncEnvelopeEncryptor encryption =
      new AsyncEnvelopeEncryptor(
          new AsyncDataKeyProvider() {
            @Override
            public CompletionStage<DataKey> generate(String context) {
              return completed(() -> new DataKey(filled(32, 1), new byte[] {1}));
            }

            @Override
            public CompletionStage<byte[]> decrypt(byte[] key, String context) {
              return completed(() -> filled(32, 1));
            }
          },
          Duration.ofMinutes(1),
          10000,
          CLOCK);
  CompletableFuture<FingerprintKeyState> pendingRead;
  CompletableFuture<PutOutcome> pendingWrite;
  CompletableFuture<Void> pendingAudit;
  CompletableFuture<AsyncTokenBudget.Permit> pendingAdmission;
  CompletableFuture<Void> pendingRelease;
  boolean auditDown;
  boolean logDown;
  boolean limiterDown;
  boolean limited;
  final AsyncTokenVault storage =
      new AsyncTokenVault() {
        @Override
        public CompletionStage<FingerprintKeyState> keyState(String keyset) {
          return pendingRead == null ? completed(() -> store.keyState(keyset)) : pendingRead;
        }

        @Override
        public CompletionStage<Optional<VaultRecord>> findByFingerprint(
            VaultScope scope, List<KeyedHash> candidates) {
          return completed(() -> store.findByFingerprint(scope, candidates));
        }

        @Override
        public CompletionStage<PutOutcome> putIfAbsent(
            VaultRecord record, FingerprintKeyState state, List<KeyedHash> others) {
          if (pendingWrite == null)
            return completed(() -> store.putIfAbsent(record, state, others));
          store.putIfAbsent(
              record, state, others); // Con commit, pero el acuse del transporte está pendiente.
          return pendingWrite;
        }

        @Override
        public CompletionStage<Optional<VaultRecord>> find(VaultScope scope, Token token) {
          return completed(() -> store.find(scope, token));
        }

        @Override
        public CompletionStage<DeleteOutcome> delete(VaultRecord expected) {
          return completed(() -> store.delete(expected));
        }
      };
  final AsyncTokenBudget budget =
      new AsyncTokenBudget() {
        @Override
        public CompletionStage<Permit> acquire(
            TokenCaller caller, TokenOperation operation, VaultScope scope) {
          if (pendingAdmission != null) return pendingAdmission;
          return completed(
              () -> {
                if (limiterDown) throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
                if (limited) throw TokenErrors.failure(TokenErrors.LIMITED);
                return permit();
              });
        }

        @Override
        public CompletionStage<Void> beforeWrite(TokenCaller caller, VaultScope scope) {
          writesBudget.incrementAndGet();
          return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean distributed() {
          return false;
        }
      };
  final TokenProfile profile =
      new TokenProfile() {
        @Override
        public String id() {
          return "dni.pe";
        }

        @Override
        public int version() {
          return 1;
        }

        @Override
        public SensitiveValue canonicalize(SensitiveValue input) {
          var result = TextTokenProfiles.dniPe().canonicalize(input);
          canonicalValues.add(result);
          return result;
        }
      };
  final VaultPolicy policy =
      new VaultPolicy(
          List.of(policy(DNI, profile)), (c, o, t, d, p) -> {}, indexes, "SCOPE_tokens:", CLOCK);
  final AsyncVaultTokenizer engine =
      AsyncVaultTokenizer.builder()
          .policy(policy)
          .vault(storage)
          .encryption(encryption)
          .tokens(new SecureTokenGenerator())
          .budget(budget)
          .audit(
              entry -> {
                if (auditDown)
                  return CompletableFuture.failedFuture(
                      new IllegalStateException("synthetic audit failure"));
                events.add(entry);
                return pendingAudit == null
                    ? CompletableFuture.completedFuture(null)
                    : pendingAudit;
              })
          .reconciliation(
              entry -> {
                if (logDown)
                  return CompletableFuture.failedFuture(
                      new IllegalStateException("synthetic reconciliation failure"));
                uncertainties.add(entry);
                return CompletableFuture.completedFuture(null);
              })
          .alarm(entry -> alarms.incrementAndGet())
          .operationIds(random::operationId)
          .build();

  AsyncTokenBudget.Permit permit() {
    return () -> {
      released.incrementAndGet();
      return pendingRelease == null ? CompletableFuture.completedFuture(null) : pendingRelease;
    };
  }

  static <T> CompletionStage<T> completed(Supplier<T> action) {
    try {
      return CompletableFuture.completedFuture(action.get());
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(failure);
    }
  }

  CompletableFuture<TokenizationResult> tokenize(String raw, String tenant) {
    try (SensitiveValue value = SensitiveValue.of(raw)) {
      return engine
          .tokenize(caller(tenant), DNI, value, DOMAIN, random.operationId().toString())
          .toCompletableFuture();
    } // La operación asíncrona debe poseer su snapshot antes de que este método retorne.
  }

  static void code(String expected, CompletionStage<?> work) {
    var error = assertThrows(CompletionException.class, () -> work.toCompletableFuture().join());
    assertEquals(expected, ((AxizException) error.getCause()).code().value());
  }

  @AfterEach
  void close() {
    encryption.close();
    indexes.close();
  }

  @Test
  void confirmedMutationsWithUnavailableAuditRequireReconciliation() {
    auditDown = true;
    code(TokenErrors.UNCERTAIN.code().value(), tokenize("00883344", "one"));
    assertEquals(
        UncertainTokenOperation.Reason.COMMITTED_AUDIT_UNCONFIRMED,
        uncertainties.getLast().reason());
    var token = uncertainties.getLast().token();
    assertEquals(1, store.records.size());
    code(TokenErrors.UNCERTAIN.code().value(), engine.delete(caller("one"), DNI, token, DOMAIN));
    assertTrue(store.records.isEmpty());
    assertEquals(UncertainTokenOperation.Phase.DELETE, uncertainties.getLast().phase());
    assertEquals(
        UncertainTokenOperation.Reason.COMMITTED_AUDIT_UNCONFIRMED,
        uncertainties.getLast().reason());
  }

  @Test
  void nativeProtocolDeduplicates100CallsAndKeepsExplicitCallerAcrossThreads() throws Exception {
    List<Future<TokenizationResult>> work = new ArrayList<>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 100; i++)
        work.add(executor.submit(() -> tokenize("00883344", "one").join()));
      Set<Token> tokens = new HashSet<>();
      int created = 0;
      for (var future : work) {
        var result = future.get();
        tokens.add(result.token());
        if (result.created()) created++;
      }
      assertEquals(1, tokens.size());
      assertEquals(1, created);
      assertNotEquals(tokens.iterator().next(), tokenize("00883344", "two").join().token());
    }
    assertEquals(101, released.get());
    assertEquals(101, events.size());
    canonicalValues.forEach(value -> assertThrows(IllegalStateException.class, value::reveal));
  }

  @Test
  void noRevealBeforeStrictAckOrSharedLeaseReleaseAndFailureDiscardsValue() {
    Token token = tokenize("00883344", "one").join().token();
    pendingAudit = new CompletableFuture<>();
    pendingRelease = new CompletableFuture<>();
    var result =
        engine
            .detokenize(caller("one"), DNI, token, DOMAIN, "customer-support")
            .toCompletableFuture();
    assertFalse(result.isDone());
    pendingAudit.complete(null);
    assertFalse(result.isDone());
    pendingRelease.complete(null);
    try (var revealed = result.join()) {
      assertEquals("00883344", revealed.reveal());
    }
    pendingAudit = null;
    pendingRelease = null;
    auditDown = true;
    code(
        "AXZ-TOKEN-0503", engine.detokenize(caller("one"), DNI, token, DOMAIN, "customer-support"));
    assertEquals(3, released.get());
  }

  @Test
  void cancellationWaitsForAcceptedReadThenClosesCanonicalAndDoesNotStartNextIo() {
    pendingRead = new CompletableFuture<>();
    var result = tokenize("00883344", "one");
    assertFalse(result.isDone());
    assertEquals(1, canonicalValues.size());
    assertTrue(result.cancel(true));
    assertFalse(pendingRead.isCancelled());
    assertEquals(0, released.get()); // La operación aceptada aún posee su lease distribuido.
    pendingRead.complete(store.state);
    assertEquals(1, released.get());
    assertEquals(0, store.writes.get());
    assertEquals(0, store.reads.get());
    assertEquals(TokenAuditEntry.Result.CANCELLED, events.getLast().result());
    assertThrows(IllegalStateException.class, canonicalValues.getFirst()::reveal);
  }

  @Test
  void cancellingPendingAdmissionReleasesLateLeaseAndAvoidsStorage() {
    pendingAdmission = new CompletableFuture<>();
    var result = tokenize("00883344", "one");
    result.cancel(true);
    pendingAdmission.complete(permit());
    assertEquals(1, released.get());
    assertEquals(0, store.reads.get());
    assertEquals(0, store.writes.get());
    assertTrue(canonicalValues.isEmpty());
  }

  @Test
  void cancelledWriteStillAuditsConfirmedCommitAndNeverCompensates() {
    pendingWrite = new CompletableFuture<>();
    var result = tokenize("00883344", "one");
    assertEquals(1, store.records.size());
    assertFalse(result.isDone());
    result.cancel(true);
    assertFalse(pendingWrite.isCancelled());
    pendingWrite.complete(new PutOutcome.Created());
    assertEquals(1, released.get());
    assertEquals(TokenAuditEntry.Result.CREATED, events.getLast().result());
    assertEquals(1, store.records.size());
    assertEquals(1, store.references.size());
    assertThrows(IllegalStateException.class, canonicalValues.getFirst()::reveal);
  }

  @Test
  void lostAckAndFailedReconciliationStayUncertainWithoutRetry() {
    pendingWrite = new CompletableFuture<>();
    var result = tokenize("00883344", "one");
    pendingWrite.completeExceptionally(new VaultUncertainException());
    code("AXZ-TOKEN-0504", result);
    assertEquals(1, uncertainties.size());
    assertEquals(1, store.writes.get());
    pendingWrite = new CompletableFuture<>();
    logDown = true;
    auditDown = true;
    var other = tokenize("00112233", "one");
    pendingWrite.completeExceptionally(new VaultUncertainException());
    code("AXZ-TOKEN-0504", other);
    assertEquals(1, alarms.get());
    assertEquals(2, store.writes.get());
  }

  @Test
  void readsRemainReadOnlyAndBudgetOutageCannotMeanAbsent() {
    Token token = tokenize("00883344", "one").join().token();
    int writes = store.writes.get();
    try (var existing = SensitiveValue.of("00883344");
        var absent = SensitiveValue.of("00112233")) {
      assertEquals(
          token,
          engine
              .lookup(caller("one"), DNI, existing, DOMAIN, "customer-support")
              .toCompletableFuture()
              .join()
              .orElseThrow());
      assertTrue(
          engine
              .lookup(caller("one"), DNI, absent, DOMAIN, "customer-support")
              .toCompletableFuture()
              .join()
              .isEmpty());
      assertEquals(writes, store.writes.get());
      int reads = store.reads.get();
      limiterDown = true;
      code("AXZ-TOKEN-0503", engine.lookup(caller("one"), DNI, absent, DOMAIN, "customer-support"));
      assertEquals(reads, store.reads.get());
      limiterDown = false;
      limited = true;
      code("AXZ-TOKEN-0429", tokenize("00112233", "one"));
      assertEquals(reads, store.reads.get());
    }
  }

  @Test
  void describeDeleteAndCiphertextScopeSubstitutionFollowSameContract() {
    Token first = tokenize("00883344", "one").join().token();
    Token second = tokenize("00112233", "one").join().token();
    assertEquals(
        first,
        engine
            .describe(caller("one"), DNI, first, DOMAIN)
            .toCompletableFuture()
            .join()
            .orElseThrow()
            .token());
    assertTrue(
        engine.describe(caller("two"), DNI, first, DOMAIN).toCompletableFuture().join().isEmpty());
    VaultRecord record = store.records.get(first);
    store.records.put(
        first,
        VaultRecord.builder()
            .token(first)
            .scope(record.scope())
            .keysetId("main")
            .fingerprints(record.fingerprints())
            .revision(record.revision())
            .ciphertext(store.records.get(second).ciphertext())
            .createdAt(record.createdAt())
            .expiresAt(record.expiresAt())
            .metadata(record.metadata())
            .build());
    code(
        "AXZ-TOKEN-0503", engine.detokenize(caller("one"), DNI, first, DOMAIN, "customer-support"));
    assertTrue(engine.delete(caller("one"), DNI, first, DOMAIN).toCompletableFuture().join());
    assertFalse(engine.delete(caller("one"), DNI, first, DOMAIN).toCompletableFuture().join());
    assertEquals(1, store.records.size());
    assertEquals(1, store.references.size());
  }

  @Test
  void staleBarrierAndOrphanAreHandledWithoutCrossScopeResult() {
    store.staleOnce = true;
    Token token = tokenize("00883344", "one").join().token();
    assertEquals("new", store.records.get(token).fingerprints().getFirst().keyId());
    store.orphan = true;
    code("AXZ-TOKEN-0503", tokenize("00883344", "one"));
    assertEquals(TokenAuditEntry.Result.INTEGRITY_FAILURE, events.getLast().result());
    store.orphan = false;
    store.constantCollision = true;
    int writes = store.writes.get();
    code("AXZ-TOKEN-0503", tokenize("00112233", "one"));
    assertEquals(writes + 3, store.writes.get());
  }
}
