package pe.axiz.core.persistence.r2dbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.test.r2dbc.FakeR2dbc;
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
  void unboundParametersFailWithTheirName() {
    R2dbcSql sql = new R2dbcSql(new FakeR2dbc().onUpdate(".*", b -> 0L));
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> sql.update("UPDATE t SET a = :a", SqlParams.of()).block());
    assertTrue(e.getMessage().contains(":a"), e.getMessage());
  }

  /** Report case: a failed commit must still return the connection to the pool, exactly once. */
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
