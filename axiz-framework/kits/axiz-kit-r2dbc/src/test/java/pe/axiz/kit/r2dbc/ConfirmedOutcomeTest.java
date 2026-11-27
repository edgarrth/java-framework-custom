package pe.axiz.kit.r2dbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.R2dbcDataIntegrityViolationException;
import io.r2dbc.spi.R2dbcRollbackException;
import io.r2dbc.spi.ValidationDepth;
import io.r2dbc.spi.Wrapped;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import pe.axiz.test.support.r2dbc.FakeR2dbc;
import reactor.core.publisher.Mono;

class ConfirmedOutcomeTest {
  @Test
  void failedCommitAndPhysicalCloseReleaseARealPoolSlotOnBothTransactionApis() {
    for (boolean reactive : List.of(true, false)) {
      var physicalConnections = new java.util.ArrayList<Connection>();
      ConnectionFactory factory = mock(ConnectionFactory.class);
      when(factory.getMetadata()).thenReturn(() -> "test");
      when(factory.create())
          .thenAnswer(
              ignored ->
                  Mono.defer(
                      () -> {
                        var closed = new AtomicBoolean();
                        Connection physical = mock(Connection.class);
                        physicalConnections.add(physical);
                        when(physical.beginTransaction()).thenReturn(Mono.empty());
                        when(physical.commitTransaction())
                            .thenReturn(
                                Mono.error(
                                    new IllegalStateException("lost commit acknowledgement")));
                        when(physical.rollbackTransaction()).thenReturn(Mono.empty());
                        when(physical.validate(any(ValidationDepth.class)))
                            .thenAnswer(call -> Mono.just(!closed.get()));
                        when(physical.close())
                            .thenReturn(
                                Mono.defer(
                                    () -> {
                                      if (closed.getAndSet(true)) return Mono.empty();
                                      return Mono.error(
                                          new IllegalStateException("lost close acknowledgement"));
                                    }));
                        return Mono.just(physical);
                      }));
      var pool =
          new ConnectionPool(
              ConnectionPoolConfiguration.builder(factory)
                  .initialSize(0)
                  .maxSize(1)
                  .maxAcquireTime(Duration.ofSeconds(2))
                  .build());
      try {
        assertThrows(
            TransactionOutcomeUnknownException.class,
            () -> {
              if (reactive)
                new R2dbcSql(pool)
                    .inTransaction(tx -> Mono.just("work"))
                    .block(Duration.ofSeconds(3));
              else
                new R2dbcTransactionRunner(pool, Duration.ofSeconds(2)).inTransaction(() -> "work");
            });
        Connection first = physicalConnections.getFirst();
        var order = inOrder(first);
        order.verify(first).beginTransaction();
        order.verify(first).commitTransaction();
        order.verify(first).close();
        Connection replacement = pool.create().block(Duration.ofSeconds(3));
        assertEquals(2, physicalConnections.size());
        assertEquals(1, pool.getMetrics().orElseThrow().acquiredSize());
        Mono.from(replacement.close()).onErrorComplete().block(Duration.ofSeconds(3));
      } finally {
        pool.disposeLater().onErrorComplete().block(Duration.ofSeconds(3));
      }
    }
  }

  @Test
  void aPhysicalCloseErrorDoesNotLeakARealPoolSlotWhenTheConnectionIsAlreadyClosed() {
    ConnectionFactory factory = mock(ConnectionFactory.class);
    var allocated = new AtomicInteger();
    when(factory.getMetadata()).thenReturn(() -> "test");
    when(factory.create())
        .thenAnswer(
            ignored ->
                Mono.defer(
                    () -> {
                      allocated.incrementAndGet();
                      var closed = new AtomicBoolean();
                      Connection physical = mock(Connection.class);
                      when(physical.validate(any(ValidationDepth.class)))
                          .thenAnswer(call -> Mono.just(!closed.get()));
                      when(physical.close())
                          .thenReturn(
                              Mono.defer(
                                  () -> {
                                    if (closed.getAndSet(true)) return Mono.empty();
                                    return Mono.error(
                                        new IllegalStateException("close acknowledgement failed"));
                                  }));
                      return Mono.just(physical);
                    }));
    var pool =
        new ConnectionPool(
            ConnectionPoolConfiguration.builder(factory)
                .initialSize(0)
                .maxSize(1)
                .maxAcquireTime(Duration.ofSeconds(2))
                .build());
    try {
      Connection first = pool.create().block(Duration.ofSeconds(3));
      DiscardConnection.close(first).onErrorComplete().block(Duration.ofSeconds(3));
      Connection second = pool.create().block(Duration.ofSeconds(3));
      assertEquals(2, allocated.get());
      assertEquals(1, pool.getMetrics().orElseThrow().acquiredSize());
      Mono.from(second.close()).onErrorComplete().block(Duration.ofSeconds(3));
    } finally {
      pool.disposeLater().onErrorComplete().block(Duration.ofSeconds(3));
    }
  }

  @Test
  void aStillLiveUncertainConnectionIsNeverReturnedAfterAnUnsuccessfulPhysicalClose() {
    Connection physical = mock(Connection.class);
    Connection pooled = mock(Connection.class, withSettings().extraInterfaces(Wrapped.class));
    when(((Wrapped<?>) pooled).unwrap()).thenAnswer(ignored -> physical);
    when(physical.close()).thenReturn(Mono.error(new IllegalStateException("close failed")));
    when(physical.validate(ValidationDepth.LOCAL)).thenReturn(Mono.just(true));
    assertThrows(IllegalStateException.class, () -> DiscardConnection.close(pooled).block());
    when(physical.validate(ValidationDepth.LOCAL)).thenReturn(Mono.empty());
    assertThrows(IllegalStateException.class, () -> DiscardConnection.close(pooled).block());
    verify(pooled, never()).close();
  }

  @Test
  void uncertainCommitAndFailedRollbackClosePhysicalConnectionBeforePoolRelease() {
    for (boolean commit : List.of(true, false)) {
      for (boolean reactive : List.of(true, false)) {
        Connection physical = mock(Connection.class);
        Connection pooled = mock(Connection.class, withSettings().extraInterfaces(Wrapped.class));
        when(((Wrapped<?>) pooled).unwrap()).thenAnswer(ignored -> physical);
        ConnectionFactory factory = mock(ConnectionFactory.class);
        when(factory.create()).thenAnswer(ignored -> Mono.just(pooled));
        when(pooled.beginTransaction()).thenReturn(Mono.empty());
        when(pooled.commitTransaction())
            .thenReturn(Mono.error(new IllegalStateException("transport failed")));
        when(pooled.rollbackTransaction())
            .thenReturn(Mono.error(new IllegalStateException("rollback failed")));
        when(pooled.close()).thenReturn(Mono.empty());
        when(physical.close()).thenReturn(Mono.empty());
        assertThrows(
            RuntimeException.class,
            () -> {
              if (reactive)
                new R2dbcSql(factory)
                    .inTransaction(
                        tx ->
                            commit
                                ? Mono.just("work")
                                : Mono.error(new IllegalStateException("business failed")))
                    .block();
              else
                new R2dbcTransactionRunner(factory, Duration.ofSeconds(1))
                    .inTransaction(
                        () -> {
                          if (!commit) throw new IllegalStateException("business failed");
                          return "work";
                        });
            });
        var order = inOrder(physical, pooled);
        order.verify(physical).close();
        order.verify(pooled).close();
      }
    }
  }

  @Test
  void cleanupCannotCauseRetryOfCommittedBusinessWork() throws Exception {
    var db = new FakeR2dbc().failOn("close");
    var calls = new AtomicInteger();
    var sql = new R2dbcSql(db);
    assertEquals(
        1, sql.inTransaction(tx -> Mono.fromCallable(calls::incrementAndGet)).retry(1).block());
    assertEquals(1, calls.get());
    assertEquals(List.of("open:1", "begin:1", "commit:1", "close:1"), db.events());
    var imperative = new FakeR2dbc().failOn("close");
    assertEquals(
        "confirmed",
        new R2dbcTransactionRunner(imperative, Duration.ofSeconds(1))
            .inTransaction(() -> "confirmed"));
    assertEquals(List.of("open:1", "begin:1", "commit:1", "close:1"), imperative.events());
  }

  @Test
  void emptyConfirmedTransactionAlsoPreservesItsResult() {
    var db = new FakeR2dbc().failOn("close");
    assertNull(new R2dbcSql(db).inTransaction(tx -> Mono.empty()).retry(1).block());
    assertEquals(List.of("open:1", "begin:1", "commit:1", "close:1"), db.events());
  }

  @Test
  void autocommitSuccessSurvivesCleanupAndDoesNotRepeatTheStatement() {
    var db = new FakeR2dbc().onUpdate("UPDATE", ignored -> 1L).failOn("close");
    assertEquals(1L, new R2dbcSql(db).update("UPDATE t SET n=1", SqlParams.of()).retry(1).block());
    assertEquals(1, db.executions().size());
  }

  @Test
  void sameFactoryNestedTransactionHasOnlyOneOwnerAndRollsBackTogether() {
    var db = new FakeR2dbc();
    var outer = new R2dbcSql(db);
    var inner = new R2dbcSql(db);
    assertThrows(
        IllegalStateException.class,
        () ->
            outer
                .inTransaction(
                    tx ->
                        inner
                            .inTransaction(nested -> Mono.just("done"))
                            .then(Mono.error(new IllegalStateException("abort outer"))))
                .block());
    assertEquals(List.of("open:1", "begin:1", "rollback:1", "close:1"), db.events());
  }

  @Test
  void explicitServerRollbackRemainsDefiniteWhileCompletionUnknownStaysUncertain() {
    for (RuntimeException rejection :
        List.of(
            new R2dbcRollbackException("serialization rejected", "40001"),
            new R2dbcDataIntegrityViolationException("deferred constraint", "23505"))) {
      ConnectionFactory factory = mock(ConnectionFactory.class);
      Connection connection = mock(Connection.class);
      when(factory.create()).thenAnswer(ignored -> Mono.just(connection));
      when(connection.beginTransaction()).thenReturn(Mono.empty());
      when(connection.commitTransaction()).thenReturn(Mono.error(rejection));
      when(connection.close()).thenReturn(Mono.empty());
      assertSame(
          rejection,
          assertThrows(
              RuntimeException.class,
              () -> new R2dbcSql(factory).inTransaction(tx -> Mono.just("work")).block()));
      assertSame(
          rejection,
          assertThrows(
              RuntimeException.class,
              () ->
                  new R2dbcTransactionRunner(factory, Duration.ofSeconds(1))
                      .inTransaction(() -> "work")));
      when(connection.commitTransaction())
          .thenReturn(Mono.error(new R2dbcRollbackException("unknown", "40003")));
      assertThrows(
          TransactionOutcomeUnknownException.class,
          () -> new R2dbcSql(factory).inTransaction(tx -> Mono.just("work")).block());
    }
  }

  @Test
  void cancellingUncommittedWorkRollsBackAndClosesOnce() {
    var db = new FakeR2dbc();
    var subscription = new R2dbcSql(db).inTransaction(tx -> Mono.never()).subscribe();
    subscription.dispose();
    assertEquals(List.of("open:1", "begin:1", "rollback:1", "close:1"), db.events());
  }
}
