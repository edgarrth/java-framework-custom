package pe.axiz.adapter.outbox.r2dbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.audit.AuditIntegrity;
import pe.axiz.kit.r2dbc.R2dbcSql;
import pe.axiz.test.support.r2dbc.FakeR2dbc;
import reactor.core.publisher.Mono;

class ReactiveAuditStateTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-18T00:00:00Z"), ZoneOffset.UTC);
  private static final Duration TIMEOUT = Duration.ofSeconds(1);
  private static final String CHAIN = "payments";
  private static final String HASH = "a".repeat(64);

  private static R2dbcSql active(FakeR2dbc db) {
    var sql = spy(new R2dbcSql(db));
    // Transaction semantics are exercised against PostgreSQL in PostgresAuditChainIT.
    // Here the caller's transaction state is controlled to test chain protocol failures.
    doReturn(Mono.just(true)).when(sql).isTransactionActive();
    return sql;
  }

  private static FakeR2dbc database(long updated) {
    return new FakeR2dbc()
        .onUpdate("^INSERT INTO audit_heads", binds -> 1L)
        .onQuery(
            "FOR UPDATE",
            binds ->
                List.of(
                    Map.of("last_sequence", 0L, "last_hash", AuditIntegrity.initial(CHAIN).hash())))
        .onUpdate("^UPDATE audit_heads", binds -> updated);
  }

  private static AuditIntegrity successor(AuditIntegrity head) {
    return new AuditIntegrity(head.chainId(), head.sequence() + 1, head.hash(), HASH, null, null);
  }

  @Test
  void advancementIsLazyRequiresATransactionAndPersistsOneSuccessor() {
    var db = database(1);
    var sql = active(db);
    var state = new R2dbcAuditChainState(sql, db, "audit_heads", CLOCK, TIMEOUT);
    assertSame(db, state.transactionResource());
    var pending = state.advance(CHAIN, ReactiveAuditStateTest::successor);
    assertTrue(db.executions().isEmpty());
    assertEquals(successor(AuditIntegrity.initial(CHAIN)), pending.block());
    var update = db.last("UPDATE audit_heads");
    assertEquals(1L, update.binds().get(0));
    assertEquals(HASH, update.binds().get(1));
    assertEquals(CLOCK.instant(), update.binds().get(2));
    assertEquals(CHAIN, update.binds().get(3));
    doReturn(Mono.just(false)).when(sql).isTransactionActive();
    assertThrows(
        IllegalStateException.class,
        () -> state.advance(CHAIN, ReactiveAuditStateTest::successor).block());
  }

  @Test
  void missingOrChangedHeadsAndNonSuccessorsCannotProduceAcknowledgement() {
    var missing =
        new FakeR2dbc().onUpdate("^INSERT", binds -> 1L).onQuery("FOR UPDATE", binds -> List.of());
    var absent = new R2dbcAuditChainState(active(missing), missing, "audit_heads", CLOCK, TIMEOUT);
    assertThrows(
        IllegalStateException.class,
        () -> absent.advance(CHAIN, ReactiveAuditStateTest::successor).block());
    var db = database(0);
    var state = new R2dbcAuditChainState(active(db), db, "audit_heads", CLOCK, TIMEOUT);
    assertThrows(
        IllegalStateException.class,
        () -> state.advance(CHAIN, ReactiveAuditStateTest::successor).block());
    assertThrows(IllegalArgumentException.class, () -> state.advance(CHAIN, head -> head).block());
    assertThrows(NullPointerException.class, () -> state.advance(CHAIN, head -> null).block());
  }

  @Test
  void checkpointsUseCommittedHeadsAndCloseTheirIndependentConnection() {
    var db =
        new FakeR2dbc()
            .onQuery(
                "SELECT last_sequence",
                binds -> List.of(Map.of("last_sequence", 7L, "last_hash", HASH)));
    var state = new R2dbcAuditChainState(new R2dbcSql(db), db, "audit_heads", CLOCK, TIMEOUT);
    var checkpoint = state.checkpoint(CHAIN).block();
    assertEquals(7, checkpoint.sequence());
    assertEquals(HASH, checkpoint.hash());
    assertEquals(CLOCK.instant(), checkpoint.at());
    assertEquals(List.of("open:1", "close:1"), db.events());
    var empty = new FakeR2dbc().onQuery("SELECT last_sequence", binds -> List.of());
    var initial =
        new R2dbcAuditChainState(new R2dbcSql(empty), empty, "audit_heads", CLOCK, TIMEOUT)
            .checkpoint(CHAIN)
            .block();
    assertEquals(0, initial.sequence());
    assertEquals(AuditIntegrity.initial(CHAIN).hash(), initial.hash());
  }

  @Test
  void checkpointsRejectBorrowingAnActiveTransactionAndStillClose() {
    var factory = mock(ConnectionFactory.class);
    var connection = mock(Connection.class);
    doReturn(Mono.just(connection)).when(factory).create();
    when(connection.isAutoCommit()).thenReturn(false);
    when(connection.close()).thenReturn(Mono.empty());
    var state =
        new R2dbcAuditChainState(new R2dbcSql(factory), factory, "audit_heads", CLOCK, TIMEOUT);
    assertThrows(IllegalStateException.class, () -> state.checkpoint(CHAIN).block());
    verify(connection).close();
  }

  @Test
  void boundedDeadlinesAndSharedTransactionIdentityAreMandatory() {
    var db = new FakeR2dbc();
    var sql = new R2dbcSql(db);
    assertThrows(
        IllegalArgumentException.class,
        () -> new R2dbcAuditChainState(sql, new FakeR2dbc(), "audit_heads", CLOCK, TIMEOUT));
    for (Duration timeout : new Duration[] {Duration.ZERO, Duration.ofMinutes(1).plusNanos(1)}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new R2dbcAuditChainState(sql, db, "audit_heads", CLOCK, timeout));
    }
    assertSame(
        db,
        new R2dbcAuditChainState(sql, db, "audit_heads", CLOCK, Duration.ofMinutes(1))
            .transactionResource());
  }
}
