package pe.axiz.test.support.r2dbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.IsolationLevel;
import io.r2dbc.spi.Parameters;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import io.r2dbc.spi.ValidationDepth;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * El propio driver fake: aquello en lo que confían otras pruebas cuando verifican SQL, bindings y
 * eventos.
 */
class FakeR2dbcTest {

  @Test
  void executionOwnsItsBindMapsAndPreservesTypedNulls() {
    Map<Integer, Object> binds = new HashMap<>();
    binds.put(0, null);
    Map<Integer, Class<?>> types = new HashMap<>(Map.of(0, String.class));
    var execution = new FakeR2dbc.Execution("SELECT $1", binds, types, 1);
    binds.put(0, "changed");
    types.clear();
    assertTrue(execution.binds().containsKey(0));
    assertNull(execution.binds().get(0));
    assertEquals(String.class, execution.types().get(0));
    assertThrows(UnsupportedOperationException.class, () -> execution.binds().put(0, "changed"));
    assertThrows(UnsupportedOperationException.class, () -> execution.types().clear());
  }

  private static Connection open(FakeR2dbc db) {
    return Mono.from(db.create()).cast(Connection.class).block();
  }

  @Test
  void recordsUpdatesWithTheirBindsTypesAndConnection() {
    FakeR2dbc db = new FakeR2dbc().onUpdate("^UPDATE account", binds -> 2L);
    Connection c = open(db);
    Statement s =
        c.createStatement("UPDATE account SET owner = $1, note = $2, limit_ = $3 WHERE id = $4")
            .bind(0, "ana")
            .bind(1, Parameters.in(String.class))
            .bindNull(2, Long.class)
            .bind(3, 7L);
    Long updated = Mono.from(s.execute()).flatMap(r -> Mono.from(r.getRowsUpdated())).block();

    assertEquals(2L, updated);
    FakeR2dbc.Execution e = db.last("UPDATE account");
    assertEquals("ana", e.binds().get(0));
    assertNull(e.binds().get(1), "a typed null is unwrapped like a driver does");
    assertEquals(String.class, e.types().get(1));
    assertNull(e.binds().get(2));
    assertEquals(Long.class, e.types().get(2));
    assertEquals(7L, e.binds().get(3));
    assertEquals(1, e.connection());
    assertEquals(List.of(e), db.executions());
  }

  @Test
  void answersQueriesWithRowsReadByNameOrPosition() {
    Map<String, Object> row = new HashMap<>();
    row.put("id", "e-1");
    row.put("attempts", 3);
    FakeR2dbc db =
        new FakeR2dbc()
            .onQuery("SELECT .* FROM outbox", binds -> List.of(row, Map.of("id", "e-2")));
    Connection c = open(db);
    List<String> read =
        Flux.from(c.createStatement("SELECT id, attempts FROM outbox").execute())
            .flatMap(
                r ->
                    r.map(
                        (rw, meta) ->
                            rw.get("id", String.class)
                                + ":"
                                + rw.get("attempts", Long.class)
                                + ":"
                                + rw.get("attempts", Integer.class)))
            .collectList()
            .block();
    assertEquals(List.of("e-1:3:3", "e-2:null:null"), read);

    Object first =
        Flux.from(c.createStatement("SELECT id FROM outbox").execute())
            .flatMap(r -> r.map((rw, meta) -> rw.get(0, Object.class)))
            .blockFirst();
    assertTrue(first.equals("e-1") || first.equals(3), "the first column of the row map");
  }

  @Test
  void connectionEventsAreRecordedAndCanFail() {
    FakeR2dbc db = new FakeR2dbc().failOn("commit");
    Connection c = open(db);
    Mono.from(c.beginTransaction()).block();
    assertThrows(IllegalStateException.class, () -> Mono.from(c.commitTransaction()).block());
    Mono.from(c.rollbackTransaction()).block();
    Mono.from(c.createSavepoint("s")).block();
    Mono.from(c.close()).block();
    assertEquals(
        List.of("open:1", "begin:1", "commit:1", "rollback:1", "savepoint:1", "close:1"),
        db.events());

    Connection second = open(db);
    Mono.from(second.beginTransaction(IsolationLevel.SERIALIZABLE)).block();
    assertTrue(db.events().contains("begin:2"));
  }

  @Test
  void reportsItselfAsPostgresql() {
    FakeR2dbc db = new FakeR2dbc();
    Connection c = open(db);
    assertEquals("PostgreSQL", db.getMetadata().getName());
    assertEquals("PostgreSQL", c.getMetadata().getDatabaseProductName());
    assertEquals("1", c.getMetadata().getDatabaseVersion());
    assertTrue(c.isAutoCommit());
    assertEquals(IsolationLevel.READ_COMMITTED, c.getTransactionIsolationLevel());
    assertTrue(Mono.from(c.validate(ValidationDepth.LOCAL)).block());
    // la configuración se acepta y se ignora
    Mono.from(c.setAutoCommit(false)).block();
    Mono.from(c.setLockWaitTimeout(Duration.ofSeconds(1))).block();
    Mono.from(c.setStatementTimeout(Duration.ofSeconds(1))).block();
    Mono.from(c.setTransactionIsolationLevel(IsolationLevel.SERIALIZABLE)).block();
    Mono.from(c.releaseSavepoint("s")).block();
    Mono.from(c.rollbackTransactionToSavepoint("s")).block();
  }

  @Test
  void unexpectedSqlAndUnsupportedFeaturesFailLoudly() {
    FakeR2dbc db = new FakeR2dbc().onQuery("SELECT 1", binds -> List.of(Map.of("x", 1)));
    Connection c = open(db);
    AssertionError unmatched =
        assertThrows(
            AssertionError.class,
            () -> Mono.from(c.createStatement("DELETE FROM x").execute()).block());
    assertTrue(unmatched.getMessage().contains("DELETE FROM x"));
    assertThrows(AssertionError.class, () -> db.last("never ran"));
    assertThrows(UnsupportedOperationException.class, c::createBatch);
    Statement s = c.createStatement("SELECT 1");
    assertThrows(UnsupportedOperationException.class, s::add);
    assertThrows(UnsupportedOperationException.class, () -> s.bind("x", 1));
    assertThrows(UnsupportedOperationException.class, () -> s.bindNull("x", String.class));

    Result result = Mono.from(s.execute()).block();
    assertEquals(result, result.filter(segment -> true));
    assertThrows(
        UnsupportedOperationException.class, () -> result.flatMap(segment -> Mono.empty()));
    Object metadata =
        Flux.from(
                result.map(
                    (row, meta) -> meta.getColumnMetadatas().size() + ":" + meta.contains("x")))
            .blockFirst();
    assertEquals("0:false", metadata);
  }
}
