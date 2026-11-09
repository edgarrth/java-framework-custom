package pe.axiz.adapter.outbox.r2dbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import pe.axiz.adapter.outbox.jdbc.Dialect;
import pe.axiz.adapter.outbox.jdbc.JdbcOutboxStore;
import pe.axiz.core.commons.tx.TransactionRunner;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PublishResult;
import pe.axiz.core.outbox.OutboxAdmin;
import pe.axiz.core.outbox.OutboxRelay;
import pe.axiz.core.outbox.OutboxStore;
import pe.axiz.core.outbox.OutboxWriter;
import pe.axiz.core.resilience.Backoff;
import pe.axiz.kit.jdbc.JdbcTransactions;
import pe.axiz.kit.r2dbc.R2dbcSql;
import pe.axiz.kit.r2dbc.R2dbcTransactionRunner;

/** PostgreSQL semantics, independently exercised through both production drivers. */
@Timeout(180)
class PostgresOutboxOrderingIT {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17.6-alpine");
  private static PGSimpleDataSource source;
  private static ConnectionFactory connections;
  private static final Clock CLOCK = Clock.systemUTC();

  @BeforeAll
  static void start() {
    boolean docker = org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
    if (Boolean.getBoolean("axiz.it.requireDocker"))
      org.junit.jupiter.api.Assertions.assertTrue(docker, "Docker is required");
    org.junit.jupiter.api.Assumptions.assumeTrue(docker, "Docker unavailable");
    POSTGRES.start();
    new java.security.SecureRandom().nextBytes(new byte[32]);
    reactor.blockhound.BlockHound.install();
    source = new PGSimpleDataSource();
    source.setUrl(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    connections =
        ConnectionFactories.get(
            io.r2dbc.spi.ConnectionFactoryOptions.builder()
                .option(io.r2dbc.spi.ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(io.r2dbc.spi.ConnectionFactoryOptions.HOST, POSTGRES.getHost())
                .option(io.r2dbc.spi.ConnectionFactoryOptions.PORT, POSTGRES.getMappedPort(5432))
                .option(io.r2dbc.spi.ConnectionFactoryOptions.DATABASE, POSTGRES.getDatabaseName())
                .option(io.r2dbc.spi.ConnectionFactoryOptions.USER, POSTGRES.getUsername())
                .option(io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD, POSTGRES.getPassword())
                .build());
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @BeforeEach
  void schema() throws Exception {
    try (var c = source.getConnection();
        var s = c.createStatement()) {
      s.execute("DROP TABLE IF EXISTS axiz_outbox");
      s.execute("DROP TABLE IF EXISTS axiz_outbox_aggregate");
    }
    script("outbox-postgresql.sql");
  }

  private static void script(String name) throws Exception {
    try (var c = source.getConnection();
        var stmt = c.createStatement();
        InputStream input = JdbcOutboxStore.class.getResourceAsStream("/axiz/sql/" + name)) {
      assertNotNull(input);
      c.setAutoCommit(false);
      for (String sql :
          new String(input.readAllBytes(), StandardCharsets.UTF_8)
              .replaceAll("(?m)--.*", "")
              .split(";")) if (!sql.replaceAll("--.*", "").isBlank()) stmt.execute(sql);
      c.commit();
    }
  }

  private record Backend(OutboxStore store, TransactionRunner tx) {}

  private Backend backend(boolean reactive) {
    if (reactive)
      return new Backend(
          new R2dbcOutboxStore(new R2dbcSql(connections), "axiz_outbox", Duration.ofSeconds(20)),
          new R2dbcTransactionRunner(connections, Duration.ofSeconds(20)));
    var tx = new JdbcTransactions(source);
    return new Backend(new JdbcOutboxStore(tx, "axiz_outbox", Dialect.POSTGRESQL), tx);
  }

  private static EventEnvelope event(String id, String aggregate) {
    return new EventEnvelope(
        id,
        "urn:test",
        "ordered.v1",
        CLOCK.instant(),
        aggregate,
        "application/json",
        "{}".getBytes(StandardCharsets.UTF_8),
        Map.of());
  }

  private OutboxRelay relay(Backend b, EventPublisher publisher) {
    return new OutboxRelay(
        b.store(),
        publisher,
        b.tx(),
        CLOCK,
        100,
        2,
        Backoff.fixed(Duration.ZERO),
        Duration.ofMinutes(3),
        Duration.ofMillis(1));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void databaseLeaseIsIndependentOfTheApplicationClockInBothDirections(boolean reactive)
      throws Exception {
    Backend b = backend(reactive);
    for (long hours : new long[] {-1, 1}) {
      String id = "skew-" + hours;
      new OutboxWriter(b.store(), CLOCK).write("t", event(id, id), "order");
      var claimed =
          b.tx().inTransaction(() -> b.store().claimDue(1, Duration.ofMinutes(1), "owner"));
      assertEquals(1, claimed.size());
      try (var c = source.getConnection();
          var statement = c.createStatement();
          var rows =
              statement.executeQuery(
                  "SELECT lease_until > statement_timestamp() AND "
                      + "lease_until <= statement_timestamp() + interval '61 seconds' FROM axiz_outbox WHERE id='"
                      + id
                      + "'")) {
        assertTrue(rows.next());
        assertTrue(rows.getBoolean(1));
      }
      var sent = new ArrayList<String>();
      var relay =
          new OutboxRelay(
              b.store(),
              (destination, e) -> {
                sent.add(e.id());
                return new PublishResult(e.id(), destination, null);
              },
              b.tx(),
              Clock.offset(CLOCK, Duration.ofHours(hours)),
              10,
              2,
              Backoff.fixed(Duration.ZERO),
              Duration.ofMinutes(3),
              Duration.ofMillis(1));
      assertEquals(
          0, relay.relayOnce().published(), "app clock cannot steal an unexpired database lease");
      try (var c = source.getConnection();
          var statement = c.createStatement()) {
        statement.executeUpdate(
            "UPDATE axiz_outbox SET lease_until=statement_timestamp()-interval '1 second' WHERE id='"
                + id
                + "'");
      }
      assertEquals(1, relay.relayOnce().published());
      assertEquals(List.of(id), sent);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void twoRelaysPreserveOrderForOneThousandEventsAcrossFiftyAggregates(boolean reactive)
      throws Exception {
    Backend b = backend(reactive);
    b.store().verifySchema();
    var writer = new OutboxWriter(b.store(), CLOCK);
    b.tx()
        .inTransaction(
            () -> {
              for (int n = 0; n < 20; n++)
                for (int a = 0; a < 50; a++)
                  writer.write("t", event(a + "-" + n, "account:" + a), "account:tenant");
              return null;
            });
    Map<String, List<Long>> received = new ConcurrentHashMap<>();
    EventPublisher broker =
        (destination, e) -> {
          received
              .computeIfAbsent(e.subject(), key -> Collections.synchronizedList(new ArrayList<>()))
              .add(Long.parseLong(e.extension("axizseq").orElseThrow()));
          return new PublishResult(e.id(), destination, null);
        };
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = workers.submit(() -> relay(b, broker).drain());
      var second = workers.submit(() -> relay(b, broker).drain());
      assertEquals(1000, first.get(150, TimeUnit.SECONDS) + second.get(150, TimeUnit.SECONDS));
    }
    assertEquals(50, received.size());
    for (var sequences : received.values())
      assertEquals(LongStream.rangeClosed(1, 20).boxed().toList(), sequences);
    assertEquals(0, b.store().countPending());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void counterSerializesWritersAndSurvivesRollbackAndPurge(boolean reactive) throws Exception {
    Backend b = backend(reactive);
    var writer = new OutboxWriter(b.store(), CLOCK);
    var firstWritten = new CountDownLatch(1);
    var secondStarted = new CountDownLatch(1);
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          workers.submit(
              () ->
                  b.tx()
                      .inTransaction(
                          () -> {
                            writer.write("t", event("first", "same"), "a");
                            firstWritten.countDown();
                            assertTrue(secondStarted.await(20, TimeUnit.SECONDS));
                            return null;
                          }));
      var second =
          workers.submit(
              () -> {
                assertTrue(firstWritten.await(20, TimeUnit.SECONDS));
                secondStarted.countDown();
                return b.tx()
                    .inTransaction(
                        () -> {
                          writer.write("t", event("second", "same"), "a");
                          return null;
                        });
              });
      first.get(30, TimeUnit.SECONDS);
      second.get(30, TimeUnit.SECONDS);
    }
    assertThrows(
        IllegalStateException.class,
        () ->
            b.tx()
                .inTransaction(
                    () -> {
                      writer.write("t", event("rolledback", "same"), "a");
                      throw new IllegalStateException("rollback");
                    }));
    List<Long> received = new ArrayList<>();
    EventPublisher broker =
        (d, e) -> {
          received.add(Long.valueOf(e.extension("axizseq").orElseThrow()));
          return new PublishResult(e.id(), d, null);
        };
    assertEquals(2, relay(b, broker).drain());
    assertEquals(List.of(1L, 2L), received);
    assertEquals(2, b.store().purgePublished(CLOCK.instant().plusSeconds(1)));
    b.tx()
        .inTransaction(
            () -> {
              writer.write("t", event("after-purge", "same"), "a");
              return null;
            });
    assertEquals(1, relay(b, broker).drain());
    assertEquals(List.of(1L, 2L, 3L), received);
  }

  @Test
  void upgradeRetainsFailedDiagnosticsAndReportsPublishedSuccessors() throws Exception {
    try (var c = source.getConnection();
        var s = c.createStatement()) {
      s.execute("DROP TABLE axiz_outbox");
      s.execute("DROP TABLE axiz_outbox_aggregate");
      s.execute(
          "CREATE TABLE axiz_outbox(id VARCHAR(64) PRIMARY KEY,destination TEXT,aggregate_type "
              + "VARCHAR(100),aggregate_id VARCHAR(200),event_type TEXT,envelope TEXT,status "
              + "VARCHAR(16),attempts INT,created_at TIMESTAMPTZ,next_attempt_at TIMESTAMPTZ,"
              + "published_at TIMESTAMPTZ,last_error TEXT)");
      s.execute(
          "INSERT INTO axiz_outbox VALUES ('a','t','a','same','e','{}','FAILED',7,NOW(),NOW(),"
              + "NULL,'preserved'),('b','t','a','same','e','{}','PUBLISHED',0,NOW(),NOW(),NOW(),NULL),"
              + "('c','t',NULL,'partial','e','{}','PENDING',0,NOW(),NOW(),NULL,NULL)");
    }
    script("outbox-postgresql-upgrade-preflight.sql");
    script("outbox-postgresql-upgrade-sequence.sql");
    Backend b = backend(false);
    b.store().verifySchema();
    try (var c = source.getConnection();
        var s = c.createStatement();
        var rows =
            s.executeQuery(
                "SELECT status,attempts,last_error,aggregate_seq FROM axiz_outbox WHERE id='a'")) {
      assertTrue(rows.next());
      assertEquals("FAILED", rows.getString(1));
      assertEquals(7, rows.getInt(2));
      assertEquals("preserved", rows.getString(3));
      assertEquals(1, rows.getLong(4));
    }
    assertEquals(1, b.store().countBlockedAggregates());
    b.tx()
        .inTransaction(
            () -> {
              new OutboxWriter(b.store(), CLOCK).write("t", event("new", "same"), "a");
              return null;
            });
    assertTrue(
        b.tx().inTransaction(() -> b.store().claimDue(10, Duration.ofMinutes(1), "relay")).stream()
            .noneMatch(r -> r.id().equals("new")));
  }

  @Test
  void nativeRecoveryKeepsFailedDiagnosticsAndFencesOldOwners() throws Exception {
    Backend backend = backend(true);
    var writer = new OutboxWriter(backend.store(), CLOCK);
    writer.write("t", event("first", "same"), "order");
    writer.write("t", event("second", "same"), "order");
    var now = CLOCK.instant();
    assertEquals(
        1,
        backend
            .tx()
            .inTransaction(() -> backend.store().claimDue(10, Duration.ofMinutes(1), "owner"))
            .size());
    assertTrue(!backend.store().markPublished("first", "old-owner", now));
    assertTrue(!backend.store().markFailed("first", "old-owner", 1, now, "stale", true));
    assertTrue(backend.store().markFailed("first", "owner", 1, now, "original failure", true));
    var rejected =
        new OutboxAdmin(
            backend.store(),
            backend.tx(),
            (action, id, reason, at) -> {
              throw new IllegalStateException("audit rejected");
            },
            CLOCK);
    assertThrows(IllegalStateException.class, () -> rejected.skip("first", "approved"));
    assertEquals(1, backend.store().countBlockedAggregates());
    assertEquals(0, backend.store().countSkipped());
    assertThrows(IllegalArgumentException.class, () -> backend.store().skip("first", " "));
    assertThrows(
        IllegalArgumentException.class, () -> backend.store().skip("first", "x".repeat(1001)));
    assertTrue(backend.store().retry("first"));
    assertTrue(!backend.store().retry("first"));
    assertEquals(
        1,
        backend
            .tx()
            .inTransaction(() -> backend.store().claimDue(10, Duration.ofMinutes(1), "next"))
            .size());
    assertTrue(backend.store().markFailed("first", "next", 2, now, null, true));
    assertTrue(backend.store().skip("first", "approved"));
    assertEquals(1, backend.store().countSkipped());
    assertTrue(!backend.store().skip("first", "again"));
    assertTrue(!backend.store().retry("first"));
    assertEquals(
        1,
        relay(backend, (destination, event) -> new PublishResult(event.id(), destination, null))
            .drain());
    assertTrue(!backend.store().retry("second"));
    assertEquals(0, backend.store().countBlockedAggregates());
  }

  @Test
  void nativeClaimRejectsInvalidReservationsBeforeAnyDatabaseCall() {
    var sql = new R2dbcSql(connections);
    var store = new R2dbcOutboxStore(sql, "axiz_outbox", Duration.ofSeconds(5));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.claim(0, Duration.ofSeconds(1), "owner").block());
    assertThrows(
        IllegalArgumentException.class, () -> store.claim(1, Duration.ZERO, "owner").block());
    assertThrows(
        IllegalArgumentException.class, () -> store.claim(1, Duration.ofSeconds(1), " ").block());
  }
}
