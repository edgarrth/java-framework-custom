package pe.axiz.adapter.outbox.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PublishException;
import pe.axiz.core.messaging.PublishResult;
import pe.axiz.core.outbox.OutboxAdmin;
import pe.axiz.core.outbox.OutboxException;
import pe.axiz.core.outbox.OutboxRelay;
import pe.axiz.core.outbox.OutboxWriteTracing;
import pe.axiz.core.outbox.OutboxWriter;
import pe.axiz.core.resilience.Backoff;
import pe.axiz.kit.jdbc.JdbcTransactions;

class OutboxTest {

  private JdbcTransactions tx;
  private JdbcOutboxStore store;
  private final MutableClock clock = new MutableClock();
  private final List<String> published = new ArrayList<>();
  private final List<EventEnvelope> relayed = new ArrayList<>();
  private boolean brokerDown;

  private final EventPublisher publisher =
      (destination, event) -> {
        if (brokerDown) {
          throw new PublishException("down", new RuntimeException());
        }
        published.add(destination + ":" + event.id());
        relayed.add(event);
        return new PublishResult(event.id(), destination, null);
      };

  @BeforeEach
  void setUp() throws Exception {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:outbox-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement();
        InputStream in = getClass().getResourceAsStream("/axiz/sql/outbox-h2.sql")) {
      for (String sql : new String(in.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
        if (!sql.replaceAll("--.*", "").isBlank()) {
          s.execute(sql);
        }
      }
    }
    tx = new JdbcTransactions(ds);
    store = new JdbcOutboxStore(tx, "axiz_outbox", Dialect.H2);
  }

  private EventEnvelope event(String id) {
    return new EventEnvelope(
        id,
        "urn:s",
        "order-placed.v1",
        clock.instant(),
        "agg-1",
        "application/json",
        "{\"amount\":\"10.00\"}".getBytes(StandardCharsets.UTF_8),
        Map.of());
  }

  @Test
  void aFailedPredecessorPreventsItsSuccessorFromBeingPublished() {
    OutboxWriter writer = new OutboxWriter(store, clock);
    writer.write("t", event("first"), "order");
    writer.write("t", event("second"), "order");
    EventPublisher failsFirst =
        (destination, envelope) -> {
          if (envelope.id().equals("first"))
            throw new PublishException("down", new IllegalStateException());
          published.add(envelope.id());
          return new PublishResult(envelope.id(), destination, null);
        };
    new OutboxRelay(
            store,
            failsFirst,
            tx,
            clock,
            10,
            3,
            Backoff.fixed(Duration.ofSeconds(1)),
            Duration.ofMinutes(3),
            Duration.ofMillis(1))
        .relayOnce();
    assertTrue(published.isEmpty(), "the successor must wait for its failed predecessor");
  }

  @Test
  void brokerPublicationDoesNotHoldTheDatabaseTransaction() {
    new OutboxWriter(store, clock).write("t", event("first"), "order");
    EventPublisher checksTransaction =
        (destination, envelope) -> {
          try {
            Connection connection = tx.get();
            boolean autoCommit = connection.getAutoCommit();
            if (autoCommit) connection.close();
            assertTrue(autoCommit, "network publication must run outside the database transaction");
          } catch (java.sql.SQLException failure) {
            throw new IllegalStateException(failure);
          }
          return new PublishResult(envelope.id(), destination, null);
        };
    new OutboxRelay(
            store,
            checksTransaction,
            tx,
            clock,
            10,
            3,
            Backoff.fixed(Duration.ofSeconds(1)),
            Duration.ofMinutes(3),
            Duration.ofMillis(1))
        .relayOnce();
  }

  @Test
  void eventIsRecordedOnlyIfTransactionCommits() throws Exception {
    OutboxWriter writer = new OutboxWriter(store, clock);
    tx.inTransaction(
        () -> {
          writer.write("payments.events", event("e-1"), "order");
          return null;
        });
    assertThrows(
        IllegalStateException.class,
        () ->
            tx.inTransaction(
                () -> {
                  writer.write("payments.events", event("e-2"), "order");
                  throw new IllegalStateException("business failure after writing");
                }));
    assertEquals(1, store.countPending());
  }

  @Test
  void theTraceContextOfTheWriteTravelsWithTheStoredEvent() throws Exception {
    String parent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    OutboxWriteTracing tracing =
        (destination, event, write) ->
            write.accept(event.withExtension(EventEnvelope.OUTBOX_TRACEPARENT, parent));
    new OutboxWriter(store, clock, tracing).write("t", event("e-1"), "order");
    new OutboxRelay(
            store,
            publisher,
            tx,
            clock,
            10,
            3,
            Backoff.fixed(Duration.ofSeconds(10)),
            Duration.ofMinutes(3),
            Duration.ofMillis(1))
        .relayOnce();
    // the relay hands it to the (observed) publisher, which continues that trace
    assertEquals(
        parent, relayed.getFirst().extension(EventEnvelope.OUTBOX_TRACEPARENT).orElseThrow());
  }

  @Test
  void relayPublishesInOrderAndMarks() throws Exception {
    OutboxWriter writer = new OutboxWriter(store, clock);
    writer.write("t", event("e-1"), "order");
    clock.now = clock.now.plusMillis(1);
    writer.write("t", event("e-2"), "order");
    OutboxRelay relay =
        new OutboxRelay(
            store,
            publisher,
            tx,
            clock,
            10,
            3,
            Backoff.fixed(Duration.ofSeconds(10)),
            Duration.ofMinutes(3),
            Duration.ofMillis(1));
    OutboxRelay.RunResult r = relay.relayOnce();
    assertEquals(2, r.published());
    assertEquals(List.of("t:e-1", "t:e-2"), published);
    assertEquals(0, store.countPending());
    assertEquals(0, relay.relayOnce().total());
    clock.now = clock.now.plus(Duration.ofDays(8));
    assertEquals(2, store.purgePublished(clock.now.minus(Duration.ofDays(7))));
  }

  @Test
  void failuresAreRetriedWithBackoffThenMarkedFailed() throws Exception {
    clock.now = Instant.now();
    new OutboxWriter(store, clock).write("t", event("e-1"), null);
    OutboxRelay relay =
        new OutboxRelay(
            store,
            publisher,
            tx,
            clock,
            10,
            2,
            Backoff.fixed(Duration.ofSeconds(30)),
            Duration.ofMinutes(3),
            Duration.ofMillis(1));
    brokerDown = true;
    assertEquals(1, relay.relayOnce().retried());
    assertEquals(0, relay.relayOnce().total(), "not due before the backoff");
    clock.now = clock.now.plusSeconds(31);
    try (Connection c = tx.get(); Statement statement = c.createStatement()) {
      statement.executeUpdate("UPDATE axiz_outbox SET next_attempt_at=DATEADD(SECOND,-1,CURRENT_TIMESTAMP)");
    }
    assertEquals(1, relay.relayOnce().failed());
    assertEquals(0, store.countPending());
    try (Connection c = tx.get();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT status, attempts, last_error FROM axiz_outbox")) {
      rs.next();
      assertEquals("FAILED", rs.getString(1));
      assertEquals(2, rs.getInt(2));
      assertTrue(rs.getString(3).contains("down"));
    }
  }

  @Test
  void drainProcessesBacklogInBatches() {
    OutboxWriter writer = new OutboxWriter(store, clock);
    for (int i = 0; i < 25; i++) {
      writer.write("t", event("e-" + i), null);
    }
    assertEquals(
        25,
        new OutboxRelay(
                store,
                publisher,
                tx,
                clock,
                10,
                3,
                Backoff.fixed(Duration.ZERO),
                Duration.ofMinutes(3),
                Duration.ofMillis(1))
            .drain());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OutboxRelay(
                store,
                publisher,
                tx,
                clock,
                0,
                3,
                Backoff.fixed(Duration.ZERO),
                Duration.ofMinutes(3),
                Duration.ofMillis(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new JdbcOutboxStore(tx, "bad name", Dialect.POSTGRESQL));
  }

  @Test
  void sqlErrorsAreWrapped() {
    JdbcOutboxStore broken = new JdbcOutboxStore(tx, "missing_table", Dialect.H2);
    assertThrows(OutboxException.class, broken::countPending);
  }

  @Test
  void recoveryIsAuditedAtomicAndCannotReopenTerminalRows() throws Exception {
    OutboxWriter writer = new OutboxWriter(store, clock);
    writer.write("t", event("failed"), "order");
    writer.write("t", event("successor"), "order");
    brokerDown = true;
    new OutboxRelay(
            store,
            publisher,
            tx,
            clock,
            10,
            1,
            Backoff.fixed(Duration.ZERO),
            Duration.ofMinutes(3),
            Duration.ofMillis(1))
        .relayOnce();
    assertEquals(1, store.countBlockedAggregates());
    OutboxAdmin unavailableAudit =
        new OutboxAdmin(
            store,
            tx,
            (action, id, reason, at) -> {
              throw new IllegalStateException("audit unavailable");
            },
            clock);
    assertThrows(
        IllegalStateException.class, () -> unavailableAudit.skip("failed", "operator decision"));
    assertEquals(1, store.countBlockedAggregates(), "state change rolls back with audit");
    List<String> audit = new ArrayList<>();
    OutboxAdmin admin =
        new OutboxAdmin(
            store,
            tx,
            (action, id, reason, at) -> audit.add(action + ":" + id + ":" + reason),
            clock);
    assertThrows(IllegalArgumentException.class, () -> admin.skip("failed", " "));
    assertTrue(admin.retry("failed"));
    assertEquals(1, audit.size());
    new OutboxRelay(
            store,
            publisher,
            tx,
            clock,
            10,
            1,
            Backoff.fixed(Duration.ZERO),
            Duration.ofMinutes(3),
            Duration.ofMillis(1))
        .relayOnce();
    assertTrue(admin.skip("failed", "cancelled by business owner"));
    assertEquals(0, store.countBlockedAggregates());
    assertEquals(1, store.countSkipped());
    assertEquals(false, admin.retry("failed"));
    assertEquals(false, admin.skip("failed", "again"));
    brokerDown = false;
    assertEquals(
        1,
        new OutboxRelay(
                store,
                publisher,
                tx,
                clock,
                10,
                1,
                Backoff.fixed(Duration.ZERO),
                Duration.ofMinutes(3),
                Duration.ofMillis(1))
            .relayOnce()
            .published());
    assertEquals(false, admin.retry("successor"));
    assertEquals(2, audit.size());
  }

  @Test
  void expiredOwnerCannotCompleteAReclaimedEvent() throws Exception {
    new OutboxWriter(store, clock).write("t", event("leased"), "order");
    assertEquals(
        1, tx.inTransaction(() -> store.claimDue(10, Duration.ofSeconds(1), "old")).size());
    clock.now = clock.now.plusSeconds(2);
    try (Connection c = tx.get(); Statement statement = c.createStatement()) {
      statement.executeUpdate("UPDATE axiz_outbox SET lease_until=DATEADD(SECOND,-1,CURRENT_TIMESTAMP)");
    }
    assertEquals(
        1, tx.inTransaction(() -> store.claimDue(10, Duration.ofSeconds(1), "new")).size());
    assertEquals(false, store.markPublished("leased", "old", clock.now));
    assertTrue(store.markPublished("leased", "new", clock.now));
    assertEquals(false, store.markFailed("leased", "new", 1, clock.now, "late", true));
  }

  @Test
  void purgingHistoryDoesNotResetSequenceAndRollbackDoesNotConsumeIt() throws Exception {
    OutboxWriter writer = new OutboxWriter(store, clock);
    assertThrows(
        IllegalStateException.class,
        () ->
            tx.inTransaction(
                () -> {
                  writer.write("t", event("rolled-back"), "order");
                  throw new IllegalStateException("rollback");
                }));
    writer.write("t", event("first"), "order");
    new OutboxRelay(
            store,
            publisher,
            tx,
            clock,
            10,
            1,
            Backoff.fixed(Duration.ZERO),
            Duration.ofMinutes(3),
            Duration.ofMillis(1))
        .relayOnce();
    assertEquals("1", relayed.getFirst().extension("axizseq").orElseThrow());
    assertEquals(1, store.purgePublished(clock.now.plusSeconds(1)));
    writer.write("t", event("next"), "order");
    new OutboxRelay(
            store,
            publisher,
            tx,
            clock,
            10,
            1,
            Backoff.fixed(Duration.ZERO),
            Duration.ofMinutes(3),
            Duration.ofMillis(1))
        .relayOnce();
    assertEquals("2", relayed.getLast().extension("axizseq").orElseThrow());
  }

  @Test
  void h2UpgradeKeepsFailuresAndInitializesPersistentCounters() throws Exception {
    try (Connection c = tx.get();
        Statement stmt = c.createStatement()) {
      stmt.execute("DROP TABLE axiz_outbox_aggregate");
      stmt.execute("DROP TABLE axiz_outbox");
      stmt.execute(
          "CREATE TABLE axiz_outbox (id VARCHAR(100) PRIMARY KEY, destination VARCHAR(200),"
              + "aggregate_type VARCHAR(100),aggregate_id VARCHAR(200),event_type VARCHAR(200),"
              + "envelope CLOB,status VARCHAR(20),attempts INT,created_at TIMESTAMP WITH TIME ZONE,"
              + "next_attempt_at TIMESTAMP WITH TIME ZONE,published_at TIMESTAMP WITH TIME ZONE,"
              + "last_error VARCHAR(1000))");
      stmt.execute(
          "INSERT INTO axiz_outbox VALUES ('a','t','order','agg-1','e','{}','FAILED',5,"
              + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL,'original error'),('b','t','order','agg-1',"
              + "'e','{}','PUBLISHED',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),"
              + "('c','t',NULL,'partial','e','{}','PENDING',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,"
              + "NULL,NULL)");
      try (InputStream input =
          getClass().getResourceAsStream("/axiz/sql/outbox-h2-upgrade-sequence.sql")) {
        for (String sql :
            new String(input.readAllBytes(), StandardCharsets.UTF_8)
                .replaceAll("(?m)--.*", "")
                .split(";")) if (!sql.replaceAll("--.*", "").isBlank()) stmt.execute(sql);
      }
      store.verifySchema();
      try (ResultSet rows =
          stmt.executeQuery(
              "SELECT status,attempts,last_error,aggregate_seq FROM axiz_outbox WHERE id='a'")) {
        rows.next();
        assertEquals("FAILED", rows.getString(1));
        assertEquals(5, rows.getInt(2));
        assertEquals("original error", rows.getString(3));
        assertEquals(1, rows.getLong(4));
      }
    }
    new OutboxWriter(store, clock).write("t", event("new"), "order");
    try (Connection c = tx.get();
        Statement stmt = c.createStatement();
        ResultSet rows =
            stmt.executeQuery("SELECT aggregate_seq FROM axiz_outbox WHERE id='new'")) {
      rows.next();
      assertEquals(3, rows.getLong(1));
    }
  }

  static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Test
  void preflightReportsHistoricalSuccessorsAndNeverChangesTheFailedEvidence() throws Exception {
    var writer = new OutboxWriter(store, clock);
    writer.write("t", event("a"), "order");
    writer.write("t", event("b"), "order");
    writer.write("t", event("c"), "order");
    try (var connection = tx.get();
        var statement = connection.createStatement()) {
      statement.executeUpdate(
          "UPDATE axiz_outbox SET status='FAILED',attempts=5,last_error='original error' WHERE id='a'");
      statement.executeUpdate("UPDATE axiz_outbox SET status='PUBLISHED' WHERE id='b'");
      var failures = new ArrayList<JdbcOutboxPreflight.Failure>();
      assertEquals(1, JdbcOutboxPreflight.report(connection, "axiz_outbox", failures::add));
      var failure = failures.getFirst();
      assertEquals("a", failure.id());
      assertEquals("order", failure.aggregateType());
      assertEquals("agg-1", failure.aggregateId());
      assertEquals("original error", failure.error());
      assertEquals(5, failure.attempts());
      assertTrue(failure.publishedSuccessors());
      assertTrue(failure.pendingSuccessors());
      try (var rows =
          statement.executeQuery(
              "SELECT status,attempts,last_error FROM axiz_outbox WHERE id='a'")) {
        assertTrue(rows.next());
        assertEquals("FAILED", rows.getString(1));
        assertEquals(5, rows.getInt(2));
        assertEquals("original error", rows.getString(3));
      }
      assertThrows(
          IllegalArgumentException.class,
          () ->
              JdbcOutboxPreflight.report(
                  connection, "axiz_outbox; DROP TABLE axiz_outbox", failures::add));
    }
  }
}
