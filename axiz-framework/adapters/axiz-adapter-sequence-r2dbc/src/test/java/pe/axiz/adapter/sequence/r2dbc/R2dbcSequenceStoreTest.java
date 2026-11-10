package pe.axiz.adapter.sequence.r2dbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import pe.axiz.kit.r2dbc.R2dbcSql;
import pe.axiz.test.support.r2dbc.FakeR2dbc;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

class R2dbcSequenceStoreTest {
  @Test
  void theIndependentReservationPreservesTheCallersReactorContext() {
    var request = new AtomicReference<String>();
    var db = new FakeR2dbc().onQuery("^INSERT INTO", binds -> List.of(Map.of("seq_value", 1L)));
    ConnectionFactory contextual =
        new ConnectionFactory() {
          @Override
          public Publisher<? extends Connection> create() {
            return Mono.deferContextual(
                context -> {
                  request.set(context.get("request-id"));
                  return Mono.from(db.create());
                });
          }

          @Override
          public ConnectionFactoryMetadata getMetadata() {
            return db.getMetadata();
          }
        };
    assertEquals(
        1L,
        new R2dbcSequenceStore(contextual, "axiz_sequence")
            .increment("operation", "", 1)
            .contextWrite(Context.of("request-id", "request-42"))
            .block());
    assertEquals("request-42", request.get());
    assertEquals(List.of("open:1", "begin:1", "commit:1", "close:1"), db.events());
  }

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
    assertThrows(NoSuchElementException.class, () -> store.increment("operation", "", 1).block());
    assertEquals(List.of("open:1", "begin:1", "rollback:1", "close:1"), db.events());
  }
}
