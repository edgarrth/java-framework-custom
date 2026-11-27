package pe.axiz.core.tokenization.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.BlindIndex;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.vault.VaultMaintenanceEvent.Action;
import pe.axiz.core.tokenization.vault.VaultMaintenanceEvent.Result;

class VaultRetentionTest {
  private static final Instant NOW = Instant.parse("2026-11-17T00:00:00Z");
  private static final TokenDomain DOMAIN = new TokenDomain("payments");
  private static final TokenCaller ADMIN =
      new TokenCaller("retention", "job", "tenant", Set.of("SCOPE_tokens:purge"), Set.of(DOMAIN));
  private static final FingerprintKeyState STATE =
      new FingerprintKeyState("main", 1, "key", List.of("key"));
  private final TokenVaultMaintenance store = mock(TokenVaultMaintenance.class);
  private final List<VaultMaintenanceEvent> events = new ArrayList<>();
  private final List<UncertainVaultMaintenance> reconciliation = new ArrayList<>();

  private VaultRetention job(VaultMaintenanceAccess access, VaultMaintenanceAudit audit) {
    when(store.keyState("main")).thenReturn(STATE);
    return VaultRetention.builder()
        .vault(store)
        .access(access)
        .audit(audit)
        .reconciliation(reconciliation::add)
        .alarm(
            failure -> {
              throw new AssertionError("Unexpected reconciliation failure");
            })
        .clock(Clock.fixed(NOW, ZoneOffset.UTC))
        .ids(UUID::randomUUID)
        .authority("SCOPE_tokens:purge")
        .batchTimeout(Duration.ofSeconds(10))
        .build();
  }

  private VaultRetention job() {
    return job((caller, action, keyset, scope) -> {}, events::add);
  }

  private static VaultRecord row(int seed, Instant expires) {
    byte[] entropy = new byte[16];
    entropy[0] = (byte) seed;
    var scope = new VaultScope(new TokenType("DNI"), "tenant", DOMAIN, "dni.pe", 1);
    try (var indexes = new BlindIndex(Map.of("key", new byte[32]), "key")) {
      return VaultRecord.builder()
          .token(Token.fromEntropy(entropy))
          .scope(scope)
          .keysetId("main")
          .fingerprints(List.of(indexes.index("synthetic-" + seed, scope.indexScope(), "key")))
          .revision(1)
          .ciphertext(new byte[40])
          .createdAt(NOW.minusSeconds(100))
          .expiresAt(expires)
          .metadata(Map.of())
          .build();
    }
  }

  @Test
  void boundaryExpiryIsDeletedWithOwnedReferencesAndLiveRowsAreRetainedWithoutDecryption() {
    var expired = row(1, NOW);
    var live = row(2, NOW.plusSeconds(1));
    when(store.scan("main", null, 2))
        .thenReturn(new VaultScanPage(List.of(expired, live), "opaque-cursor"));
    when(store.delete(expired)).thenReturn(DeleteOutcome.DELETED);
    var progress = job().purge(ADMIN, STATE, null, 2);
    assertEquals(new VaultRetentionBatch(2, 1, 0, 0, 1, "opaque-cursor"), progress);
    assertFalse(progress.toString().contains("opaque-cursor"));
    verify(store, never()).delete(live);
    assertEquals(
        List.of(Result.STARTED, Result.STARTED, Result.SUCCESS, Result.SUCCESS),
        events.stream().map(VaultMaintenanceEvent::result).toList());
    assertEquals(0, reconciliation.size());
    assertFalse(events.toString().contains(expired.token().reveal()));
  }

  @Test
  void concurrentChangesAreCountedAndNeverForceDeletedOrRetried() {
    var changed = row(1, NOW);
    var absent = row(2, NOW);
    when(store.scan("main", null, 2)).thenReturn(new VaultScanPage(List.of(changed, absent), null));
    when(store.delete(changed)).thenReturn(DeleteOutcome.CHANGED);
    when(store.delete(absent)).thenReturn(DeleteOutcome.NOT_FOUND);
    assertEquals(new VaultRetentionBatch(2, 0, 1, 1, 0, null), job().purge(ADMIN, STATE, null, 2));
    verify(store, times(1)).delete(changed);
    assertEquals(Result.CONFLICT, events.get(2).result());
  }

  @Test
  void lostAcknowledgementIsReconciledOnceAndStopsThePage() {
    var expired = row(1, NOW);
    var later = row(2, NOW);
    when(store.scan("main", null, 2)).thenReturn(new VaultScanPage(List.of(expired, later), null));
    when(store.delete(expired)).thenThrow(new VaultUncertainException());
    var error = assertThrows(AxizException.class, () -> job().purge(ADMIN, STATE, null, 2));
    assertEquals(TokenErrors.UNCERTAIN.code(), error.code());
    assertEquals(1, reconciliation.size());
    assertEquals(expired.token(), reconciliation.getFirst().token());
    assertEquals(Action.PURGE, reconciliation.getFirst().event().action());
    verify(store, times(1)).delete(expired);
    verify(store, never()).delete(later);
  }

  @Test
  void missingAuthorityOrRowPermissionDeniesDeletion() {
    var expired = row(1, NOW);
    var runner =
        job(
            (caller, action, keyset, scope) -> {
              if (scope != null) throw TokenErrors.failure(TokenErrors.DENIED);
            },
            events::add);
    var unauthorized =
        new TokenCaller("reader", "job", "tenant", Set.of("SCOPE_tokens:describe"), Set.of(DOMAIN));
    assertThrows(AxizException.class, () -> runner.purge(unauthorized, STATE, null, 1));
    verify(store, never()).scan(any(), any(), anyInt());
    when(store.scan("main", null, 1)).thenReturn(new VaultScanPage(List.of(expired), null));
    assertThrows(AxizException.class, () -> runner.purge(ADMIN, STATE, null, 1));
    verify(store, never()).delete(any());
  }

  @Test
  void auditMustConfirmBeforeMutationAndCannotTurnACommittedDeleteIntoSuccess() {
    var expired = row(1, NOW);
    when(store.scan("main", null, 1)).thenReturn(new VaultScanPage(List.of(expired), null));
    var noAudit =
        job(
            (caller, action, keyset, scope) -> {},
            event -> {
              throw new IllegalStateException("audit down");
            });
    assertThrows(AxizException.class, () -> noAudit.purge(ADMIN, STATE, null, 1));
    verify(store, never()).delete(any());
    when(store.delete(expired)).thenReturn(DeleteOutcome.DELETED);
    var finalAuditFails =
        job(
            (caller, action, keyset, scope) -> {},
            event -> {
              events.add(event);
              if (event.result() == Result.SUCCESS) throw new IllegalStateException("audit down");
            });
    var error =
        assertThrows(AxizException.class, () -> finalAuditFails.purge(ADMIN, STATE, null, 1));
    assertEquals(TokenErrors.UNCERTAIN.code(), error.code());
    assertEquals(Result.SUCCESS, reconciliation.getFirst().event().result());
    verify(store, times(1)).delete(expired);
  }

  @Test
  void invalidLimitsStaleStateRepeatedCursorAndInterruptionDoNotDelete() {
    var runner = job();
    assertThrows(AxizException.class, () -> runner.purge(ADMIN, STATE, null, 101));
    var stale = new FingerprintKeyState("main", 2, "key", List.of("key"));
    assertThrows(AxizException.class, () -> runner.purge(ADMIN, stale, null, 1));
    when(store.scan("main", "cursor", 1)).thenReturn(new VaultScanPage(List.of(), "cursor"));
    assertThrows(AxizException.class, () -> runner.purge(ADMIN, STATE, "cursor", 1));
    Thread.currentThread().interrupt();
    try {
      assertThrows(AxizException.class, () -> runner.purge(ADMIN, STATE, null, 1));
    } finally {
      Thread.interrupted();
    }
    verify(store, never()).delete(any());
  }
}
