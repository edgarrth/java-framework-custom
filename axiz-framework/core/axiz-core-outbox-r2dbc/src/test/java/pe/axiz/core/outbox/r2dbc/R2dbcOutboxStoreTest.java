package pe.axiz.core.outbox.r2dbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.outbox.OutboxRecord;
import pe.axiz.core.outbox.OutboxRelay;
import pe.axiz.core.persistence.r2dbc.R2dbcSql;
import pe.axiz.core.persistence.r2dbc.R2dbcTransactionRunner;
import pe.axiz.core.resilience.Backoff;
import pe.axiz.core.test.MutableClock;
import pe.axiz.core.test.RecordingEventPublisher;
import pe.axiz.core.test.r2dbc.FakeR2dbc;

class R2dbcOutboxStoreTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private final MutableClock clock = MutableClock.at("2026-10-11T12:00:00Z");

  private static EventEnvelope event(String id) {
    return new EventEnvelope(
        id,
        "urn:axiz:payments-business",
        "pe.axiz.payments.payment-order.created.v1",
        Instant.parse("2026-10-11T12:00:00Z"),
        "order-1",
        "application/json",
        "{\"orderId\":\"order-1\"}".getBytes(StandardCharsets.UTF_8),
        Map.of());
  }

  @Test
  void writerInsertsAPendingRecordInTheCallersTransaction() {
    FakeR2dbc db = new FakeR2dbc().onUpdate("^INSERT INTO axiz_outbox", b -> 1L);
    R2dbcSql sql = new R2dbcSql(db);
    ReactiveOutboxWriter writer =
        new ReactiveOutboxWriter(new R2dbcOutboxStore(sql, "axiz_outbox", TIMEOUT), clock);

    sql.inTransaction(tx -> writer.write("payments", event("e-1"), "payment-order")).block();

    FakeR2dbc.Execution insert = db.last("INSERT INTO axiz_outbox");
    assertEquals("e-1", insert.binds().get(0));
    assertEquals("payments", insert.binds().get(1));
    assertEquals("payment-order", insert.binds().get(2));
    assertEquals("order-1", insert.binds().get(3));
    assertEquals("PENDING", insert.binds().get(6));
    assertEquals(0, insert.binds().get(7));
    assertEquals(Instant.parse("2026-10-11T12:00:00Z"), insert.binds().get(8));
    assertEquals(event("e-1").id(), CloudEventJson.read((String) insert.binds().get(5)).id());
    assertEquals(List.of("open:1", "begin:1", "commit:1", "close:1"), db.events());
  }

  @Test
  void relayLocksPublishesAndMarksInOneTransaction() {
    List<Map<Integer, Object>> marked = new ArrayList<>();
    FakeR2dbc db =
        new FakeR2dbc()
            .onQuery(
                "FROM axiz_outbox WHERE status = 'PENDING'.*FOR UPDATE SKIP LOCKED",
                b -> List.of(row("e-1"), row("e-2")))
            .onUpdate(
                "^UPDATE axiz_outbox SET status = 'PUBLISHED'",
                b -> {
                  marked.add(new HashMap<>(b));
                  return 1L;
                });
    R2dbcSql sql = new R2dbcSql(db);
    RecordingEventPublisher publisher = new RecordingEventPublisher();
    OutboxRelay relay =
        new OutboxRelay(
            new R2dbcOutboxStore(sql, "axiz_outbox", TIMEOUT),
            publisher,
            new R2dbcTransactionRunner(db, TIMEOUT),
            clock,
            10,
            3,
            Backoff.fixed(Duration.ofSeconds(5)));

    OutboxRelay.RunResult result = relay.relayOnce();

    assertEquals(2, result.published());
    assertEquals(
        List.of("e-1", "e-2"), publisher.published().stream().map(p -> p.event().id()).toList());
    assertEquals(2, marked.size());
    assertEquals(List.of("open:1", "begin:1", "commit:1", "close:1"), db.events());
    assertTrue(db.executions().stream().allMatch(e -> e.connection() == 1), "one connection");
  }

  @Test
  void failedPublicationsAreRescheduledWithATrimmedError() {
    List<Map<Integer, Object>> failed = new ArrayList<>();
    FakeR2dbc db =
        new FakeR2dbc()
            .onQuery("FOR UPDATE SKIP LOCKED", b -> List.of(row("e-1")))
            .onUpdate(
                "SET status = \\$1",
                b -> {
                  failed.add(new HashMap<>(b));
                  return 1L;
                });
    RecordingEventPublisher publisher = new RecordingEventPublisher();
    publisher.failing(true);
    OutboxRelay relay =
        new OutboxRelay(
            new R2dbcOutboxStore(new R2dbcSql(db), "axiz_outbox", TIMEOUT),
            publisher,
            new R2dbcTransactionRunner(db, TIMEOUT),
            clock,
            10,
            3,
            Backoff.fixed(Duration.ofSeconds(30)));

    assertEquals(1, relay.relayOnce().retried());
    Map<Integer, Object> b = failed.get(0);
    assertEquals("PENDING", b.get(0));
    assertEquals(1, b.get(1));
    assertEquals(Instant.parse("2026-10-11T12:00:30Z"), b.get(2));
    assertTrue(((String) b.get(3)).length() <= 1000);
    assertEquals("e-1", b.get(4));
  }

  /**
   * Regression: the blocking {@code append} (used by the imperative outbox writer) waited on a
   * {@code Mono<Void>} and failed with "returned nothing" after the row had been inserted.
   */
  @Test
  void blockingAppendCompletesForAStatementWithoutValue() {
    FakeR2dbc db = new FakeR2dbc().onUpdate("^INSERT INTO axiz_outbox", b -> 1L);
    R2dbcOutboxStore store = new R2dbcOutboxStore(new R2dbcSql(db), "axiz_outbox", TIMEOUT);
    store.append(
        new OutboxRecord(
            "e-2",
            "payments",
            "payment-order",
            "order-1",
            "pe.axiz.payments.payment-order.created.v1",
            CloudEventJson.write(event("e-2")),
            OutboxRecord.Status.PENDING,
            0,
            Instant.parse("2026-10-11T12:00:00Z"),
            Instant.parse("2026-10-11T12:00:00Z"),
            null));
    assertEquals("e-2", db.last("INSERT INTO axiz_outbox").binds().get(0));
  }

  @Test
  void aFailingStatementIsAnOutboxException() {
    FakeR2dbc db = new FakeR2dbc().failOn("close");
    db.onUpdate("^UPDATE", b -> 1L);
    R2dbcOutboxStore store = new R2dbcOutboxStore(new R2dbcSql(db), "axiz_outbox", TIMEOUT);
    org.junit.jupiter.api.Assertions.assertThrows(
        pe.axiz.core.outbox.OutboxException.class,
        () -> store.markPublished("e-1", Instant.parse("2026-10-11T12:00:00Z")));
  }

  @Test
  void countsPendingRecords() {
    FakeR2dbc db = new FakeR2dbc().onQuery("COUNT", b -> List.of(Map.of("pending", 7L)));
    assertEquals(7L, new R2dbcOutboxStore(new R2dbcSql(db), "axiz_outbox", TIMEOUT).countPending());
  }

  @Test
  void nullableColumnsMapToNull() {
    Map<String, Object> r = new HashMap<>(row("e-9"));
    r.remove("aggregate_type");
    r.remove("last_error");
    FakeR2dbc db = new FakeR2dbc().onQuery("FOR UPDATE", b -> List.of(r));
    var records =
        new R2dbcOutboxStore(new R2dbcSql(db), "axiz_outbox", TIMEOUT)
            .lockDue(5, Instant.parse("2026-10-11T12:00:00Z"));
    assertNull(records.get(0).aggregateType());
    assertNull(records.get(0).lastError());
  }

  @Test
  void aRecordThatGivesUpIsFailedWithItsErrorTrimmedOrEmpty() {
    List<Map<Integer, Object>> updates = new ArrayList<>();
    FakeR2dbc db =
        new FakeR2dbc()
            .onUpdate(
                "SET status = \\$1",
                b -> {
                  updates.add(new HashMap<>(b));
                  return 1L;
                });
    R2dbcOutboxStore store = new R2dbcOutboxStore(new R2dbcSql(db), "axiz_outbox", TIMEOUT);
    Instant next = Instant.parse("2026-10-11T12:05:00Z");

    store.markFailed("e-1", 3, next, "x".repeat(1500), true);
    store.markFailed("e-2", 1, next, "broker unavailable", false);
    store.markFailed("e-3", 1, next, null, false);

    assertEquals("FAILED", updates.get(0).get(0));
    assertEquals(1000, ((String) updates.get(0).get(3)).length());
    assertEquals("PENDING", updates.get(1).get(0));
    assertEquals("broker unavailable", updates.get(1).get(3));
    assertNull(updates.get(2).get(3));
    assertEquals(String.class, db.last("SET status").types().get(3), "a typed null for the error");
  }

  @Test
  void purgesPublishedRecordsAndCountsZeroPendingOnAnEmptyAnswer() {
    FakeR2dbc db =
        new FakeR2dbc()
            .onUpdate("^DELETE FROM axiz_outbox", b -> 4L)
            .onQuery("COUNT", b -> List.of());
    R2dbcOutboxStore store = new R2dbcOutboxStore(new R2dbcSql(db), "axiz_outbox", TIMEOUT);
    assertEquals(4, store.purgePublished(Instant.parse("2026-10-01T00:00:00Z")));
    assertEquals(
        Instant.parse("2026-10-01T00:00:00Z"), db.last("DELETE FROM axiz_outbox").binds().get(0));
    assertEquals(0L, store.countPending());
  }

  @Test
  void aRecordWithoutAttemptsHasZero() {
    Map<String, Object> r = new HashMap<>(row("e-10"));
    r.remove("attempts");
    FakeR2dbc db = new FakeR2dbc().onQuery("FOR UPDATE", b -> List.of(r));
    assertEquals(
        0,
        new R2dbcOutboxStore(new R2dbcSql(db), "axiz_outbox", TIMEOUT)
            .lockDue(5, Instant.parse("2026-10-11T12:00:00Z"))
            .get(0)
            .attempts());
  }

  private static Map<String, Object> row(String id) {
    Map<String, Object> m = new HashMap<>();
    m.put("id", id);
    m.put("destination", "payments");
    m.put("aggregate_type", "payment-order");
    m.put("aggregate_id", "order-1");
    m.put("event_type", "pe.axiz.payments.payment-order.created.v1");
    m.put("envelope", CloudEventJson.write(event(id)));
    m.put("status", "PENDING");
    m.put("attempts", 0);
    m.put("created_at", Instant.parse("2026-10-11T11:59:00Z"));
    m.put("next_attempt_at", Instant.parse("2026-10-11T11:59:00Z"));
    m.put("last_error", "previous");
    return m;
  }
}
