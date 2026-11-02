package pe.axiz.kit.r2dbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.test.support.r2dbc.FakeR2dbc;
import reactor.core.publisher.Mono;

class R2dbcSqlTest {

  @Test
  void updatesBindValuesByPositionAndNullsWithTheirType() {
    FakeR2dbc db = new FakeR2dbc().onUpdate("^UPDATE", b -> 1L);
    R2dbcSql sql = new R2dbcSql(db);

    long n =
        sql.update(
                "UPDATE t SET note = :note WHERE id = :id",
                SqlParams.of().bind("id", "a-1").bind("note", null, String.class))
            .block();

    assertEquals(1L, n);
    FakeR2dbc.Execution e = db.last("UPDATE t");
    assertEquals("UPDATE t SET note = $1 WHERE id = $2", e.sql());
    assertNull(e.binds().get(0));
    assertEquals("a-1", e.binds().get(1));
    assertEquals(List.of("open:1", "close:1"), db.events(), "own connection, closed after");
  }

  @Test
  void queriesMapRows() {
    FakeR2dbc db =
        new FakeR2dbc()
            .onQuery(
                "FROM t", b -> List.of(Map.of("id", "a", "n", 1L), Map.of("id", "b", "n", 2L)));
    List<String> rows =
        new R2dbcSql(db)
            .query(
                "SELECT id, n FROM t WHERE n > :min",
                SqlParams.of().bind("min", 0L),
                r -> r.require("id", String.class) + r.require("n", Long.class))
            .collectList()
            .block();
    assertEquals(List.of("a1", "b2"), rows);
  }

  @Test
  void transactionsCommitOnSuccessAndShareTheirConnection() {
    FakeR2dbc db = new FakeR2dbc().onUpdate("^INSERT", b -> 1L);
    R2dbcSql sql = new R2dbcSql(db);

    sql.inTransaction(
            tx ->
                tx.update("INSERT INTO t (id) VALUES (:id)", SqlParams.of().bind("id", "1"))
                    .then(
                        tx.update(
                            "INSERT INTO t (id) VALUES (:id)", SqlParams.of().bind("id", "2"))))
        .block();

    assertEquals(List.of("open:1", "begin:1", "commit:1", "close:1"), db.events());
    assertTrue(db.executions().stream().allMatch(e -> e.connection() == 1));
  }

  @Test
  void transactionsRollBackOnError() {
    FakeR2dbc db = new FakeR2dbc().onUpdate("^INSERT", b -> 1L);
    R2dbcSql sql = new R2dbcSql(db);

    assertThrows(
        IllegalStateException.class,
        () ->
            sql.inTransaction(
                    tx ->
                        tx.update("INSERT INTO t (id) VALUES (:id)", SqlParams.of().bind("id", "1"))
                            .then(Mono.error(new IllegalStateException("boom"))))
                .block());

    assertEquals(List.of("open:1", "begin:1", "rollback:1", "close:1"), db.events());
  }

  @Test
  void reactiveTransactionsDoNotRouteOtherFactoriesToTheirConnection() {
    FakeR2dbc first = new FakeR2dbc().onUpdate("^UPDATE", b -> 1L);
    FakeR2dbc second = new FakeR2dbc().onUpdate("^UPDATE", b -> 2L);
    R2dbcSql sql = new R2dbcSql(first);
    R2dbcSql other = new R2dbcSql(second);

    Long updated =
        sql.inTransaction(tx -> other.update("UPDATE other_table SET a = 1", SqlParams.of()))
            .block();

    assertEquals(2L, updated);
    assertTrue(first.executions().isEmpty());
    assertEquals(List.of("open:1", "close:1"), second.events());
    assertEquals(List.of("open:1", "begin:1", "commit:1", "close:1"), first.events());
  }

  @Test
  void nestedReactiveTransactionsPreserveEachFactoryConnection() {
    FakeR2dbc first = new FakeR2dbc().onUpdate("^UPDATE", b -> 1L);
    FakeR2dbc second = new FakeR2dbc().onUpdate("^UPDATE", b -> 2L);
    R2dbcSql sql = new R2dbcSql(first);
    R2dbcSql other = new R2dbcSql(second);

    sql.inTransaction(
            tx ->
                other
                    .inTransaction(
                        nested ->
                            nested
                                .update("UPDATE second_table SET a = 1", SqlParams.of())
                                .then(tx.update("UPDATE first_table SET a = 1", SqlParams.of())))
                    .then(tx.update("UPDATE first_table SET a = 2", SqlParams.of())))
        .block();

    assertEquals(2, first.executions().size());
    assertEquals(1, second.executions().size());
    assertEquals(List.of("open:1", "begin:1", "commit:1", "close:1"), first.events());
    assertEquals(List.of("open:1", "begin:1", "commit:1", "close:1"), second.events());
  }

  @Test
  void blockingTransactionsIsolateFactoriesAndKeepTheOuterBinding() throws Exception {
    FakeR2dbc first = new FakeR2dbc().onUpdate("^UPDATE", b -> 1L);
    FakeR2dbc second = new FakeR2dbc().onUpdate("^UPDATE", b -> 2L);
    R2dbcSql sql = new R2dbcSql(first);
    R2dbcSql other = new R2dbcSql(second);
    R2dbcTransactionRunner outer = new R2dbcTransactionRunner(first, Duration.ofSeconds(5));
    R2dbcTransactionRunner inner = new R2dbcTransactionRunner(second, Duration.ofSeconds(5));

    outer.inTransaction(
        () -> {
          assertEquals(
              2L,
              inner.inTransaction(
                  () -> {
                    assertEquals(
                        1L, sql.update("UPDATE first_table SET a = 1", SqlParams.of()).block());
                    return other.update("UPDATE second_table SET a = 1", SqlParams.of()).block();
                  }));
          assertEquals(1L, sql.update("UPDATE first_table SET a = 2", SqlParams.of()).block());
          return null;
        });

    assertEquals(List.of("open:1", "begin:1", "commit:1", "close:1"), first.events());
    assertEquals(List.of("open:1", "begin:1", "commit:1", "close:1"), second.events());
    sql.update("UPDATE first_table SET a = 3", SqlParams.of()).block();
    assertEquals(
        List.of("open:1", "begin:1", "commit:1", "close:1", "open:2", "close:2"), first.events());
  }

  @Test
  void blockingRunnerBindsTheConnectionToTheThread() throws Exception {
    FakeR2dbc db = new FakeR2dbc().onUpdate("^DELETE", b -> 3L);
    R2dbcSql sql = new R2dbcSql(db);
    R2dbcTransactionRunner runner = new R2dbcTransactionRunner(db, Duration.ofSeconds(5));

    long deleted =
        runner.inTransaction(
            () ->
                sql.update("DELETE FROM t WHERE a = :a", SqlParams.of().bind("a", 1)).block()
                    + sql.update("DELETE FROM t WHERE a = :a", SqlParams.of().bind("a", 2))
                        .block());

    assertEquals(6L, deleted);
    assertEquals(List.of("open:1", "begin:1", "commit:1", "close:1"), db.events());
  }

  @Test
  void blockingRunnerRollsBackWhenTheWorkFails() {
    FakeR2dbc db = new FakeR2dbc();
    R2dbcTransactionRunner runner = new R2dbcTransactionRunner(db, Duration.ofSeconds(5));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runner.inTransaction(
                () -> {
                  throw new IllegalArgumentException("no");
                }));
    assertEquals(List.of("open:1", "begin:1", "rollback:1", "close:1"), db.events());
  }

  @Test
  void blockingRunnersForTheSameFactoryJoinAndAnInnerFailureKeepsOtherFactoriesBound()
      throws Exception {
    FakeR2dbc first = new FakeR2dbc().onUpdate("^UPDATE", b -> 1L);
    FakeR2dbc second = new FakeR2dbc();
    R2dbcSql sql = new R2dbcSql(first);
    R2dbcTransactionRunner outer = new R2dbcTransactionRunner(first, Duration.ofSeconds(5));
    R2dbcTransactionRunner same = new R2dbcTransactionRunner(first, Duration.ofSeconds(5));
    R2dbcTransactionRunner other = new R2dbcTransactionRunner(second, Duration.ofSeconds(5));

    outer.inTransaction(
        () -> {
          same.inTransaction(() -> sql.update("UPDATE t SET a = 1", SqlParams.of()).block());
          assertThrows(
              IllegalStateException.class,
              () ->
                  other.inTransaction(
                      () -> {
                        throw new IllegalStateException("inner");
                      }));
          return sql.update("UPDATE t SET a = 2", SqlParams.of()).block();
        });

    assertEquals(List.of("open:1", "begin:1", "commit:1", "close:1"), first.events());
    assertEquals(List.of("open:1", "begin:1", "rollback:1", "close:1"), second.events());
    assertTrue(first.executions().stream().allMatch(e -> e.connection() == 1));
  }

  @Test
  void unboundParametersFailWithTheirName() {
    R2dbcSql sql = new R2dbcSql(new FakeR2dbc().onUpdate(".*", b -> 0L));
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> sql.update("UPDATE t SET a = :a", SqlParams.of()).block());
    assertTrue(e.getMessage().contains(":a"), e.getMessage());
  }

  /** Regression: a failed commit must still return the connection to the pool, exactly once. */
  @Test
  void aFailedCommitClosesTheConnectionExactlyOnceAndPropagates() {
    FakeR2dbc db = new FakeR2dbc().onUpdate("^UPDATE", b -> 1L).failOn("commit");
    R2dbcSql sql = new R2dbcSql(db);
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> sql.inTransaction(tx -> tx.update("UPDATE t SET a = 1", SqlParams.of())).block());
    assertEquals("commit failed", e.getMessage());
    assertEquals(
        List.of("open:1", "begin:1", "commit:1", "rollback:1", "close:1"),
        db.events(),
        "rollback attempted after the failed commit, connection closed once");
  }

  @Test
  void aFailedRollbackClosesTheConnectionAndKeepsTheOriginalError() {
    FakeR2dbc db = new FakeR2dbc().failOn("rollback");
    R2dbcSql sql = new R2dbcSql(db);
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                sql.inTransaction(tx -> Mono.<Long>error(new IllegalArgumentException("boom")))
                    .block());
    assertEquals("boom", e.getMessage());
    assertEquals(List.of("open:1", "begin:1", "rollback:1", "close:1"), db.events());
  }

  @Test
  void aFailedCloseAfterAFailedCommitIsSuppressedNotLost() {
    FakeR2dbc db = new FakeR2dbc().failOn("commit").failOn("close");
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> new R2dbcSql(db).inTransaction(tx -> Mono.just(1)).block());
    assertEquals("commit failed", e.getMessage());
    assertEquals("close failed", e.getSuppressed()[0].getMessage());
    assertEquals(1, db.events().stream().filter("close:1"::equals).count());
  }

  @Test
  void theBlockingRunnerClosesOnceWhenCommitFails() {
    FakeR2dbc db = new FakeR2dbc().failOn("commit");
    R2dbcTransactionRunner runner = new R2dbcTransactionRunner(db, Duration.ofSeconds(5));
    assertThrows(IllegalStateException.class, () -> runner.inTransaction(() -> 1));
    assertEquals(List.of("open:1", "begin:1", "commit:1", "close:1"), db.events());
  }
}
