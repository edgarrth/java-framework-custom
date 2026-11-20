package pe.axiz.adapter.tokenization.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.MapScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenOperation;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.vault.VaultScope;

class RedisTokenBudgetRecoveryTest {
  private static final VaultScope SCOPE =
      new VaultScope(new TokenType("DNI"), "tenant", new TokenDomain("billing"), "dni", 1);
  private static final UUID LEASE = UUID.randomUUID();
  private static final UUID OPERATION = UUID.randomUUID();
  private static final UUID INCARNATION = UUID.randomUUID();
  private static final UUID EVIDENCE = UUID.randomUUID();
  private static final String PREFIX = BudgetKeys.prefix("vault", SCOPE);
  private static final String ENCODED =
      "v2|worker-7|"
          + INCARNATION
          + "|"
          + OPERATION
          + "|LOOKUP|"
          + PREFIX
          + "subject:"
          + "a".repeat(64)
          + ":leases|"
          + PREFIX
          + "client:"
          + "b".repeat(64)
          + ":leases|1700000000|123456";

  @Test
  void inventorySeparatesLegacyAndBindsIdentityWithoutTurningAgeIntoPermission() {
    var f = new Fixture();
    UUID legacy = UUID.randomUUID();
    f.page(Map.of(LEASE.toString(), ENCODED, legacy.toString(), "1700000000.123456"));
    var inventory = f.recovery.inspect(SCOPE, "0");
    assertEquals("0", inventory.nextCursor());
    assertEquals(2, inventory.total());
    assertEquals(List.of(legacy), inventory.legacyReservations());
    var reservation = inventory.reservations().getFirst();
    assertEquals(new TokenBudgetOwner("worker-7", INCARNATION), reservation.owner());
    assertEquals(OPERATION, reservation.operationId());
    assertEquals(TokenOperation.LOOKUP, reservation.operation());
    assertEquals(new RedisTokenBudgetRecovery.ScopeIdentity("vault", PREFIX), reservation.scope());
    assertEquals(Instant.ofEpochSecond(1700000000, 123456000), reservation.acquiredAt());
    assertEquals(Duration.ZERO, reservation.age(reservation.acquiredAt().minusSeconds(1)));
    assertEquals(Duration.ofSeconds(1), reservation.age(reservation.acquiredAt().plusSeconds(1)));
    assertThrows(
        IllegalStateException.class, () -> f.recovery.reconcile(SCOPE, reservation, EVIDENCE));
    assertTrue(f.journal.isEmpty());
  }

  @Test
  void proofMustCoverTheExactScopeOwnerAndOperationBeforeAnyRedisMutation() {
    var f = new Fixture();
    var original = f.reservation();
    var changed =
        new RedisTokenBudgetRecovery.Reservation(
            original.scope(),
            original.lease(),
            original.owner(),
            UUID.randomUUID(),
            original.operation(),
            original.acquiredAt(),
            original.encoded());
    f.allowed = true;
    assertThrows(IllegalStateException.class, () -> f.recovery.reconcile(SCOPE, changed, EVIDENCE));
    var otherScope = new VaultScope(SCOPE.type(), "other-tenant", SCOPE.domain(), "dni", 1);
    assertThrows(
        IllegalStateException.class, () -> f.recovery.reconcile(otherScope, original, EVIDENCE));
    assertTrue(f.journal.isEmpty());
    verify(f.redis, never()).eval(anyString(), any(), any(String[].class), any(String[].class));
  }

  @Test
  void recoveryJournalsIntentAndDistinguishesReleasedMissingAndConflictingReservations() {
    for (long result : new long[] {3, 0, -1}) {
      var f = new Fixture();
      var reservation = f.reservation();
      f.allowed = true;
      when(f.redis.<Long>eval(
              anyString(), eq(ScriptOutputType.INTEGER), any(String[].class), any(String[].class)))
          .thenAnswer(
              call -> {
                assertEquals(List.of(RedisTokenBudgetRecovery.Stage.REQUESTED), f.journal);
                assertEquals(LEASE.toString(), ((String[]) call.getRawArguments()[3])[0]);
                assertEquals(ENCODED, ((String[]) call.getRawArguments()[3])[1]);
                return result;
              });
      var expected =
          result == 3
              ? RedisTokenBudgetRecovery.Stage.RELEASED
              : result == 0
                  ? RedisTokenBudgetRecovery.Stage.ALREADY_RELEASED
                  : RedisTokenBudgetRecovery.Stage.CONFLICT;
      assertEquals(expected, f.recovery.reconcile(SCOPE, reservation, EVIDENCE));
      assertEquals(List.of(RedisTokenBudgetRecovery.Stage.REQUESTED, expected), f.journal);
    }
  }

  @Test
  void unknownRedisOutcomeIsAuditedWithoutLeakingTransportDetailsAndIntentFailurePreventsIo() {
    var f = new Fixture();
    var reservation = f.reservation();
    f.allowed = true;
    when(f.redis.<Long>eval(
            anyString(), eq(ScriptOutputType.INTEGER), any(String[].class), any(String[].class)))
        .thenThrow(new IllegalStateException("synthetic sensitive connection detail"));
    var unknown =
        assertThrows(
            IllegalStateException.class, () -> f.recovery.reconcile(SCOPE, reservation, EVIDENCE));
    assertFalse(unknown.toString().contains("sensitive"));
    assertNull(unknown.getCause());
    assertEquals(
        List.of(RedisTokenBudgetRecovery.Stage.REQUESTED, RedisTokenBudgetRecovery.Stage.UNKNOWN),
        f.journal);
    clearInvocations(f.redis);
    var unavailableJournal =
        new RedisTokenBudgetRecovery(
            f.connection,
            "vault",
            (r, e) -> true,
            (stage, r, e) -> {
              throw new IllegalStateException("journal unavailable");
            });
    assertThrows(
        IllegalStateException.class,
        () -> unavailableJournal.reconcile(SCOPE, reservation, EVIDENCE));
    verifyNoInteractions(f.redis);
  }

  @Test
  void corruptOrUnboundedMetadataIsRejectedInsteadOfBecomingADeletionTarget() {
    for (String invalid :
        List.of(
            "broken",
            ENCODED.replace("|1700000000|", "|9223372036854775807|"),
            "x".repeat(1025),
            ENCODED.replace("123456", "1000000"),
            ENCODED.replace("123456", "-1"),
            ENCODED.replace("|LOOKUP|", "|INVALID|"),
            ENCODED.replace("subject:", "client:"),
            ENCODED.replace("v2|", "v3|"))) {
      var f = new Fixture();
      f.page(Map.of(LEASE.toString(), invalid));
      var inventory = f.recovery.inspect(SCOPE, "0");
      assertTrue(inventory.reservations().isEmpty());
      assertEquals(1, inventory.invalidEntryDigests().size());
      assertEquals(64, inventory.invalidEntryDigests().getFirst().length());
      assertTrue(f.journal.isEmpty());
    }
    var f = new Fixture();
    assertThrows(IllegalArgumentException.class, () -> f.recovery.inspect(SCOPE, "invalid"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RedisTokenBudgetRecovery(
                f.connection, "bad namespace", (r, e) -> true, (s, r, e) -> {}));
  }

  private static final class Fixture {
    @SuppressWarnings("unchecked")
    final StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);

    @SuppressWarnings("unchecked")
    final RedisCommands<String, String> redis = mock(RedisCommands.class);

    final List<RedisTokenBudgetRecovery.Stage> journal = new ArrayList<>();
    final RedisTokenBudgetRecovery recovery;
    boolean allowed;

    Fixture() {
      when(connection.getTimeout()).thenReturn(Duration.ofSeconds(1));
      when(connection.getOptions())
          .thenReturn(
              ClientOptions.builder()
                  .timeoutOptions(TimeoutOptions.enabled())
                  .requestQueueSize(100)
                  .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                  .build());
      when(connection.sync()).thenReturn(redis);
      when(redis.time()).thenReturn(List.of("1700000001", "123456"));
      recovery =
          new RedisTokenBudgetRecovery(
              connection,
              "vault",
              (reservation, evidence) -> {
                assertEquals(EVIDENCE, evidence);
                assertEquals(OPERATION, reservation.operationId());
                return allowed;
              },
              (stage, reservation, evidence) -> journal.add(stage));
    }

    @SuppressWarnings("unchecked")
    void page(Map<String, String> values) {
      MapScanCursor<String, String> page = mock(MapScanCursor.class);
      when(page.getMap()).thenReturn(values);
      when(page.getCursor()).thenReturn("0");
      when(redis.hscan(anyString(), any(ScanCursor.class), any(ScanArgs.class))).thenReturn(page);
      when(redis.hlen(anyString())).thenReturn((long) values.size());
    }

    RedisTokenBudgetRecovery.Reservation reservation() {
      page(Map.of(LEASE.toString(), ENCODED));
      return recovery.inspect(SCOPE, "0").reservations().getFirst();
    }
  }
}
