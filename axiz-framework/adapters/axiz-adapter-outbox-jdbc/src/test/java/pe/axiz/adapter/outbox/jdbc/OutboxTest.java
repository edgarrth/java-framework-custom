package pe.axiz.adapter.outbox.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.tx.TransactionRunner;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PublishException;
import pe.axiz.core.messaging.PublishResult;
import pe.axiz.core.outbox.OutboxAdmin;
import pe.axiz.core.outbox.OutboxException;
import pe.axiz.core.outbox.OutboxRecord;
import pe.axiz.core.outbox.OutboxRelay;
import pe.axiz.core.outbox.OutboxWriteTracing;
import pe.axiz.core.outbox.OutboxWriter;
import pe.axiz.core.resilience.Backoff;
import pe.axiz.kit.jdbc.JdbcTransactions;
import pe.axiz.kit.jdbc.TransactionOutcomeUnknownException;

class OutboxTest {

  @Test
  void invalidClaimAndRecoveryInputsLeavePersistedEventsUntouched() {
    new OutboxWriter(store, clock).write("t", event("unclaimed"), "order");
    assertThrows(
        IllegalArgumentException.class, () -> store.claimDue(0, Duration.ofSeconds(1), "worker"));
    assertThrows(IllegalArgumentException.class, () -> store.claimDue(1, Duration.ZERO, "worker"));
    assertThrows(
        IllegalArgumentException.class, () -> store.claimDue(1, Duration.ofSeconds(-1), "worker"));
    assertThrows(
        IllegalArgumentException.class, () -> store.claimDue(1, Duration.ofSeconds(1), " "));
    assertThrows(IllegalArgumentException.class, () -> store.skip("unclaimed", " "));
    assertThrows(IllegalArgumentException.class, () -> store.skip("unclaimed", "x".repeat(1001)));
    assertEquals(false, store.skip("unclaimed", "x".repeat(1000)));
    assertEquals(1, store.countPending());
    assertEquals("unclaimed", store.claimDue(1, Duration.ofSeconds(1), "worker").getFirst().id());
  }

  private JdbcDataSource source;
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
    source = ds;
    tx = new JdbcTransactions(ds);
    store = new JdbcOutboxStore(tx, tx, "axiz_outbox", Dialect.H2);
  }

  @Test
  void lostCommitAcknowledgmentIsUnknownAndNeverRollsBackOrRestoresAutoCommit() {
    var calls = new ArrayList<String>();
    var broken = new JdbcTransactions(failingSource(true, calls));
    var writer =
        new OutboxWriter(new JdbcOutboxStore(broken, broken, "axiz_outbox", Dialect.H2), clock);
    var failure =
        assertThrows(OutboxException.class, () -> writer.write("t", event("lost-ack"), "order"));
    assertTrue(failure.getCause() instanceof TransactionOutcomeUnknownException);
    assertEquals(List.of("commit", "abort"), calls);
    assertEquals(1, store.countPending(), "the confirmed database commit must not be retried");
  }

  @Test
  void aRunnerThatDoesNotBindTheConnectionCannotExecuteNonAtomicOutboxWork() {
    var unbound =
        new TransactionRunner() {
          @Override
          public <T> T inTransaction(Callable<T> work) throws Exception {
            return work.call();
          }
        };
    var invalid =
        new JdbcOutboxStore(new JdbcTransactions(source), unbound, "axiz_outbox", Dialect.H2);
    var failure = assertThrows(OutboxException.class, invalid::countPending);
    assertEquals("25000", ((SQLException) failure.getCause()).getSQLState());
    assertEquals(0, store.countPending());
  }

  @Test
  void checkedTransactionFailuresKeepTheirCauseAndInterruptionIsRestored() {
    for (Exception original :
        List.of(
            new Exception("transaction boundary failed"), new InterruptedException("cancelled"))) {
      var failing =
          new TransactionRunner() {
            @Override
            public <T> T inTransaction(Callable<T> work) throws Exception {
              throw original;
            }
          };
      var invalid = new JdbcOutboxStore(tx, failing, "axiz_outbox", Dialect.H2);
      try {
        var failure = assertThrows(OutboxException.class, invalid::countPending);
        assertSame(original, failure.getCause());
        assertEquals(
            original instanceof InterruptedException, Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Test
  void failedRollbackAfterDuplicateNeverRestoresAutoCommitOrCommitsTheCounter() {
    new OutboxWriter(store, clock).write("t", event("duplicate"), "order");
    var calls = new ArrayList<String>();
    var broken = new JdbcTransactions(failingSource(false, calls));
    var writer =
        new OutboxWriter(new JdbcOutboxStore(broken, broken, "axiz_outbox", Dialect.H2), clock);
    var failure =
        assertThrows(OutboxException.class, () -> writer.write("t", event("duplicate"), "order"));
    assertEquals("23505", ((SQLException) failure.getCause()).getSQLState());
    assertEquals("rollback failed", failure.getCause().getSuppressed()[0].getMessage());
    assertEquals(List.of("rollback", "abort"), calls);
    assertEquals(1, store.countPending());
    new OutboxWriter(store, clock).write("t", event("successor"), "order");
    assertEquals(
        1L, store.claimDue(1, Duration.ofMinutes(1), "owner").getFirst().aggregateSequence());
  }

  private DataSource failingSource(boolean failCommit, List<String> calls) {
    return (DataSource)
        Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              try {
                if (!method.getName().equals("getConnection")) return method.invoke(source, args);
                var actual = source.getConnection();
                return Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (connection, operation, values) -> {
                      String name = operation.getName();
                      if (name.equals("commit") || name.equals("rollback") || name.equals("abort"))
                        calls.add(name);
                      if (name.equals("setAutoCommit") && Boolean.TRUE.equals(values[0]))
                        calls.add("restore");
                      if (name.equals("commit") && failCommit) {
                        actual.commit();
                        throw new SQLException("commit acknowledgment lost", "08006");
                      }
                      if (name.equals("rollback") && !failCommit)
                        throw new SQLException("rollback failed", "08006");
                      if (name.equals("abort")) {
                        actual.close();
                        return null;
                      }
                      try {
                        return operation.invoke(actual, values);
                      } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                      }
                    });
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }

  private EventEnvelope event(String id) {
    return EventEnvelope.builder()
        .id(id)
        .source("urn:s")
        .type("order-placed.v1")
        .time(clock.instant())
        .subject("agg-1")
        .dataContentType("application/json")
        .data("{\"amount\":\"10.00\"}".getBytes(StandardCharsets.UTF_8))
        .extensions(Map.of())
        .build();
  }

  @Test
  void claimsRespectHeadOrderingRetryEligibilityAndTerminalRecovery() throws Exception {
    var writer = new OutboxWriter(store, clock);
    writer.write("t", event("head"), "order");
    writer.write("t", event("successor"), "order");
    assertEquals(
        List.of("head"),
        tx.inTransaction(() -> store.claimDue(10, Duration.ofSeconds(30), "owner")).stream()
            .map(OutboxRecord::id)
            .toList());
    store.markFailed("head", "owner", 1, Instant.now().plusSeconds(3600), null, false);
    assertTrue(
        tx.inTransaction(() -> store.claimDue(10, Duration.ofSeconds(30), "owner")).isEmpty());
    try (var connection = tx.get();
        var statement = connection.createStatement()) {
      statement.executeUpdate(
          "UPDATE axiz_outbox SET next_attempt_at=CURRENT_TIMESTAMP WHERE id='head'");
    }
    tx.inTransaction(() -> store.claimDue(10, Duration.ofSeconds(30), "owner"));
    store.markFailed("head", "owner", 2, clock.instant(), "permanent failure", true);
    assertTrue(
        tx.inTransaction(() -> store.claimDue(10, Duration.ofSeconds(30), "owner")).isEmpty());
    assertTrue(store.skip("head", "explicit recovery"));
    assertEquals(
        List.of("successor"),
        tx.inTransaction(() -> store.claimDue(10, Duration.ofSeconds(30), "owner")).stream()
            .map(OutboxRecord::id)
            .toList());
    store.markPublished("successor", "owner", clock.instant());
    assertTrue(
        tx.inTransaction(() -> store.claimDue(10, Duration.ofSeconds(30), "owner")).isEmpty());
    assertEquals(0, store.countPending());
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
    OutboxRelay.builder(store, failsFirst, tx, clock)
        .batchSize(10)
        .maxAttempts(3)
        .backoff(Backoff.fixed(Duration.ofSeconds(1)))
        .lease(Duration.ofMinutes(3))
        .publishBudget(Duration.ofMillis(1))
        .build()
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
          } catch (SQLException failure) {
            throw new IllegalStateException(failure);
          }
          return new PublishResult(envelope.id(), destination, null);
        };
    OutboxRelay.builder(store, checksTransaction, tx, clock)
        .batchSize(10)
        .maxAttempts(3)
        .backoff(Backoff.fixed(Duration.ofSeconds(1)))
        .lease(Duration.ofMinutes(3))
        .publishBudget(Duration.ofMillis(1))
        .build()
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
    OutboxRelay.builder(store, publisher, tx, clock)
        .batchSize(10)
        .maxAttempts(3)
        .backoff(Backoff.fixed(Duration.ofSeconds(10)))
        .lease(Duration.ofMinutes(3))
        .publishBudget(Duration.ofMillis(1))
        .build()
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
        OutboxRelay.builder(store, publisher, tx, clock)
            .batchSize(10)
            .maxAttempts(3)
            .backoff(Backoff.fixed(Duration.ofSeconds(10)))
            .lease(Duration.ofMinutes(3))
            .publishBudget(Duration.ofMillis(1))
            .build();
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
        OutboxRelay.builder(store, publisher, tx, clock)
            .batchSize(10)
            .maxAttempts(2)
            .backoff(Backoff.fixed(Duration.ofSeconds(30)))
            .lease(Duration.ofMinutes(3))
            .publishBudget(Duration.ofMillis(1))
            .build();
    brokerDown = true;
    assertEquals(1, relay.relayOnce().retried());
    assertEquals(0, relay.relayOnce().total(), "not due before the backoff");
    clock.now = clock.now.plusSeconds(31);
    try (Connection c = tx.get();
        Statement statement = c.createStatement()) {
      statement.executeUpdate(
          "UPDATE axiz_outbox SET next_attempt_at=DATEADD(SECOND,-1,CURRENT_TIMESTAMP)");
    }
    assertEquals(1, relay.relayOnce().failed());
    assertEquals(0, store.countPending());
    try (Connection c = tx.get();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT status, attempts, last_error FROM axiz_outbox")) {
      rs.next();
      assertEquals("FAILED", rs.getString(1));
      assertEquals(2, rs.getInt(2));
      assertEquals("PublishException", rs.getString(3));
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
        OutboxRelay.builder(store, publisher, tx, clock)
            .batchSize(10)
            .maxAttempts(3)
            .backoff(Backoff.fixed(Duration.ZERO))
            .lease(Duration.ofMinutes(3))
            .publishBudget(Duration.ofMillis(1))
            .build()
            .drain());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            OutboxRelay.builder(store, publisher, tx, clock)
                .batchSize(0)
                .maxAttempts(3)
                .backoff(Backoff.fixed(Duration.ZERO))
                .lease(Duration.ofMinutes(3))
                .publishBudget(Duration.ofMillis(1))
                .build());
    assertThrows(
        IllegalArgumentException.class,
        () -> new JdbcOutboxStore(tx, tx, "bad name", Dialect.POSTGRESQL));
  }

  @Test
  void sqlErrorsAreWrapped() {
    JdbcOutboxStore broken = new JdbcOutboxStore(tx, tx, "missing_table", Dialect.H2);
    assertThrows(OutboxException.class, broken::countPending);
  }

  @Test
  void recoveryIsAuditedAtomicAndCannotReopenTerminalRows() throws Exception {
    OutboxWriter writer = new OutboxWriter(store, clock);
    writer.write("t", event("failed"), "order");
    writer.write("t", event("successor"), "order");
    brokerDown = true;
    OutboxRelay.builder(store, publisher, tx, clock)
        .batchSize(10)
        .maxAttempts(1)
        .backoff(Backoff.fixed(Duration.ZERO))
        .lease(Duration.ofMinutes(3))
        .publishBudget(Duration.ofMillis(1))
        .build()
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
    OutboxRelay.builder(store, publisher, tx, clock)
        .batchSize(10)
        .maxAttempts(1)
        .backoff(Backoff.fixed(Duration.ZERO))
        .lease(Duration.ofMinutes(3))
        .publishBudget(Duration.ofMillis(1))
        .build()
        .relayOnce();
    assertTrue(admin.skip("failed", "cancelled by business owner"));
    assertEquals(0, store.countBlockedAggregates());
    assertEquals(1, store.countSkipped());
    assertEquals(false, admin.retry("failed"));
    assertEquals(false, admin.skip("failed", "again"));
    brokerDown = false;
    assertEquals(
        1,
        OutboxRelay.builder(store, publisher, tx, clock)
            .batchSize(10)
            .maxAttempts(1)
            .backoff(Backoff.fixed(Duration.ZERO))
            .lease(Duration.ofMinutes(3))
            .publishBudget(Duration.ofMillis(1))
            .build()
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
    try (Connection c = tx.get();
        Statement statement = c.createStatement()) {
      statement.executeUpdate(
          "UPDATE axiz_outbox SET lease_until=DATEADD(SECOND,-1,CURRENT_TIMESTAMP)");
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
    OutboxRelay.builder(store, publisher, tx, clock)
        .batchSize(10)
        .maxAttempts(1)
        .backoff(Backoff.fixed(Duration.ZERO))
        .lease(Duration.ofMinutes(3))
        .publishBudget(Duration.ofMillis(1))
        .build()
        .relayOnce();
    assertEquals("1", relayed.getFirst().extension("axizseq").orElseThrow());
    assertEquals(1, store.purgePublished(clock.now.plusSeconds(1)));
    writer.write("t", event("next"), "order");
    OutboxRelay.builder(store, publisher, tx, clock)
        .batchSize(10)
        .maxAttempts(1)
        .backoff(Backoff.fixed(Duration.ZERO))
        .lease(Duration.ofMinutes(3))
        .publishBudget(Duration.ofMillis(1))
        .build()
        .relayOnce();
    assertEquals("2", relayed.getLast().extension("axizseq").orElseThrow());
  }

  static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
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
          "UPDATE axiz_outbox SET status='FAILED',attempts=5,last_error='original error' WHERE"
              + " id='a'");
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
