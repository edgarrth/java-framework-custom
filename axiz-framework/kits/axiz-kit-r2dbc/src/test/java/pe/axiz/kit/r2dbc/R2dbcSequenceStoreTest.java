package pe.axiz.kit.r2dbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.test.support.r2dbc.FakeR2dbc;
import reactor.core.publisher.Mono;

class R2dbcSequenceStoreTest {
  @Test
  void reservesAnAtomicPostgresBlockOnlyWhenSubscribedAndCommitsBeforeReturning() {
    FakeR2dbc db =
        new FakeR2dbc().onQuery("^INSERT INTO", binds -> List.of(Map.of("seq_value", 10L)));
    var store = new R2dbcSequenceStore(db, "axiz_sequence");
    var reservation = store.increment("operation", "20261031", 10);
    assertTrue(db.events().isEmpty());
    assertEquals(10L, reservation.block());
    var request = db.last("INSERT INTO");
    assertTrue(
        request
            .sql()
            .contains(
                "ON CONFLICT (seq_name, seq_period) DO UPDATE SET seq_value = "
                    + "axiz_sequence.seq_value + EXCLUDED.seq_value RETURNING seq_value"));
    assertEquals(Map.of(0, "operation", 1, "20261031", 2, 10), request.binds());
    assertEquals(List.of("open:1", "begin:1", "commit:1", "close:1"), db.events());
  }

  @Test
  void aBusinessRollbackCannotRollBackTheCommittedReservation() {
    FakeR2dbc db =
        new FakeR2dbc().onQuery("^INSERT INTO", binds -> List.of(Map.of("seq_value", 10L)));
    var store = new R2dbcSequenceStore(db, "axiz_sequence");
    var business = new R2dbcSql(db);
    assertThrows(
        IllegalStateException.class,
        () ->
            business
                .inTransaction(
                    tx ->
                        store
                            .increment("operation", "", 10)
                            .then(Mono.error(new IllegalStateException("business failure"))))
                .block());
    assertEquals(
        List.of(
            "open:1",
            "begin:1",
            "open:2",
            "begin:2",
            "commit:2",
            "close:2",
            "rollback:1",
            "close:1"),
        db.events());
  }

  @Test
  void rejectsInvalidInputsAndRollsBackAnIncompleteReservation() {
    FakeR2dbc db = new FakeR2dbc().onQuery("^INSERT INTO", binds -> List.of());
    assertThrows(
        IllegalArgumentException.class, () -> new R2dbcSequenceStore(db, "unsafe;DROP TABLE"));
    var store = new R2dbcSequenceStore(db, "axiz_sequence");
    assertThrows(IllegalArgumentException.class, () -> store.increment("operation", "", 0).block());
    assertTrue(db.events().isEmpty());
    assertThrows(
        java.util.NoSuchElementException.class, () -> store.increment("operation", "", 1).block());
    assertEquals(List.of("open:1", "begin:1", "rollback:1", "close:1"), db.events());
  }
}
