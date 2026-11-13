package pe.axiz.adapter.outbox.r2dbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import io.r2dbc.postgresql.client.EncodedParameter;
import io.r2dbc.postgresql.codec.Codec;
import io.r2dbc.postgresql.codec.CodecMetadata;
import io.r2dbc.postgresql.codec.PostgresTypeIdentifier;
import io.r2dbc.postgresql.message.Format;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Option;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
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
import pe.axiz.kit.jdbc.ConnectionProvider;
import pe.axiz.kit.jdbc.JdbcTransactions;
import pe.axiz.kit.r2dbc.R2dbcSql;
import pe.axiz.kit.r2dbc.R2dbcTransactionRunner;
import reactor.blockhound.BlockHound;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** PostgreSQL semantics, independently exercised through both production drivers. */
@Timeout(180)
class PostgresOutboxOrderingIT {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17.6-alpine");
  private static PGSimpleDataSource source;
  private static ConnectionFactory connections;
  private static final Clock CLOCK = Clock.systemUTC();

  @BeforeAll
  @Timeout(120)
  static void start() {
    boolean docker = DockerClientFactory.instance().isDockerAvailable();
    if (Boolean.getBoolean("axiz.it.requireDocker"))
      Assertions.assertTrue(docker, "Docker is required");
    Assumptions.assumeTrue(docker, "Docker unavailable");
    POSTGRES.start();
    new SecureRandom().nextBytes(new byte[32]);
    BlockHound.builder()
        .loadIntegrations()
        // Netty 4.2 allocator maintenance; scoped to this integration-test JVM only.
        .allowBlockingCallsInside("io.netty.buffer.AdaptivePoolingAllocator$Magazine", "free")
        .install();
    source = new PGSimpleDataSource();
    source.setUrl(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    source.setConnectTimeout(5);
    source.setLoginTimeout(5);
    source.setSocketTimeout(10);
    source.setOptions(
        "-c lock_timeout=3000 -c statement_timeout=10000"
            + " -c idle_in_transaction_session_timeout=30000 -c transaction_timeout=60000");
    connections =
        ConnectionFactories.get(
            ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(ConnectionFactoryOptions.CONNECT_TIMEOUT, Duration.ofSeconds(5))
                .option(
                    Option.valueOf("options"),
                    Map.of(
                        "lock_timeout",
                        "3s",
                        "statement_timeout",
                        "10s",
                        "idle_in_transaction_session_timeout",
                        "30s",
                        "transaction_timeout",
                        "60s"))
                .option(ConnectionFactoryOptions.HOST, POSTGRES.getHost())
                .option(ConnectionFactoryOptions.PORT, POSTGRES.getMappedPort(5432))
                .option(ConnectionFactoryOptions.DATABASE, POSTGRES.getDatabaseName())
                .option(ConnectionFactoryOptions.USER, POSTGRES.getUsername())
                .option(ConnectionFactoryOptions.PASSWORD, POSTGRES.getPassword())
                .build());
  }

  @AfterAll
  @Timeout(60)
  static void stop() {
    POSTGRES.stop();
  }

  @BeforeEach
  @Timeout(30)
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
    return new Backend(new JdbcOutboxStore(tx, tx, "axiz_outbox", Dialect.POSTGRESQL), tx);
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

  @Test
  void jdbcClaimUsesOneSelectionAndOneReturningUpdatePerRow() throws Exception {
    var backend = backend(false);
    var writer = new OutboxWriter(backend.store(), CLOCK);
    for (int n = 0; n < 3; n++)
      writer.write("t", event("returning-" + n, "aggregate-" + n), "order");
    var tx = new JdbcTransactions(source);
    var statements = new ArrayList<String>();
    ConnectionProvider observed =
        new ConnectionProvider() {
          @Override
          public Connection get() throws SQLException {
            Connection actual = tx.get();
            return (Connection)
                Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                      if (method.getName().equals("prepareStatement"))
                        statements.add((String) args[0]);
                      try {
                        return method.invoke(actual, args);
                      } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                      }
                    });
          }

          @Override
          public void release(Connection connection) throws SQLException {
            tx.release(connection.unwrap(Connection.class));
          }
        };
    var store = new JdbcOutboxStore(observed, tx, "axiz_outbox", Dialect.POSTGRESQL);
    var claimed =
        tx.inTransaction(() -> store.claimDue(3, Duration.ofMinutes(1), "returning-owner"));
    assertEquals(3, claimed.size());
    assertEquals(
        4, statements.size(), "N+1 statements, without an additional lease SELECT per row");
    assertEquals(
        3, statements.stream().filter(sql -> sql.endsWith("RETURNING lease_until")).count());
    try (var c = source.getConnection();
        var statement = c.prepareStatement("SELECT lease_until FROM axiz_outbox WHERE id=?")) {
      for (var row : claimed) {
        statement.setString(1, row.id());
        try (var rows = statement.executeQuery()) {
          assertTrue(rows.next());
          assertEquals(rows.getTimestamp(1).toInstant(), row.leaseUntil());
          assertEquals("returning-owner", row.leaseOwner());
        }
      }
    }
  }

  @Test
  void blockHoundStillRejectsApplicationBlocking() {
    var failure =
        assertThrows(
            RuntimeException.class,
            () ->
                Mono.fromRunnable(
                        () -> {
                          try {
                            new CountDownLatch(1).await(1, TimeUnit.MILLISECONDS);
                          } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                          }
                        })
                    .subscribeOn(Schedulers.parallel())
                    .block(Duration.ofSeconds(5)));
    assertTrue(failure.getCause() instanceof BlockingOperationError);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void clientDecodingFailureReleasesTransactionAndLocksWithoutStoppingTheServer(boolean fatal)
      throws Exception {
    var standard = backend(false);
    new OutboxWriter(standard.store(), CLOCK).write("t", event("pipeline-fault", "fault"), "order");
    var fired = new AtomicBoolean();
    var pid = new AtomicInteger();
    var factory =
        new PostgresqlConnectionFactory(
            PostgresqlConnectionConfiguration.builder()
                .host(POSTGRES.getHost())
                .port(POSTGRES.getMappedPort(5432))
                .database(POSTGRES.getDatabaseName())
                .username(POSTGRES.getUsername())
                .password(POSTGRES.getPassword())
                .applicationName("axiz-pipeline-fault")
                .connectTimeout(Duration.ofSeconds(5))
                .options(
                    Map.of(
                        "lock_timeout",
                        "3s",
                        "statement_timeout",
                        "10s",
                        "idle_in_transaction_session_timeout",
                        "30s",
                        "transaction_timeout",
                        "60s"))
                .codecRegistrar(
                    (connection, allocator, codecs) -> {
                      Codec<String> stringCodec = null;
                      for (Codec<?> codec : codecs) {
                        if (codec.canDecode(25, Format.FORMAT_TEXT, String.class)) {
                          @SuppressWarnings("unchecked")
                          Codec<String> typed = (Codec<String>) codec;
                          stringCodec = typed;
                          break;
                        }
                      }
                      assertNotNull(stringCodec);
                      codecs.addFirst(new FailingStringCodec(stringCodec, fired, fatal));
                      return connection
                          .createStatement("SELECT pg_backend_pid()")
                          .execute()
                          .flatMap(
                              result -> result.map((row, metadata) -> row.get(0, Integer.class)))
                          .doOnNext(pid::set)
                          .then();
                    })
                .build());
    var store = new R2dbcOutboxStore(new R2dbcSql(factory), "axiz_outbox", Duration.ofSeconds(5));
    var tx = new R2dbcTransactionRunner(factory, Duration.ofSeconds(5));
    Throwable failure =
        assertThrows(
            Throwable.class,
            () -> tx.inTransaction(() -> store.claimDue(1, Duration.ofMinutes(1), "failed-owner")));
    assertTrue(
        fired.get(), "failure must originate in the real driver decoding a locked outbox row");
    assertTrue(pid.get() > 0);
    assertTrue(
        failure.toString().contains("injected-client-decoding")
            || containsInjectedFailure(failure));
    // Three seconds is deliberately shorter than the 30-second server safety net.
    Awaitility.await()
        .atMost(Duration.ofSeconds(3))
        .untilAsserted(
            () -> {
              try (var c = source.getConnection();
                  var query =
                      c.prepareStatement(
                          "SELECT NOT EXISTS (SELECT 1 FROM pg_stat_activity"
                              + " WHERE pid=? AND xact_start IS NOT NULL), "
                              + "NOT EXISTS (SELECT 1 FROM pg_locks WHERE pid=?"
                              + " AND locktype='relation' AND granted)")) {
                query.setInt(1, pid.get());
                query.setInt(2, pid.get());
                try (var rows = query.executeQuery()) {
                  assertTrue(rows.next());
                  assertTrue(rows.getBoolean(1));
                  assertTrue(rows.getBoolean(2));
                }
              }
            });
    // The server and row remain usable, and the failed transaction has not claimed the event.
    assertEquals(
        1,
        standard
            .tx()
            .inTransaction(
                () -> standard.store().claimDue(1, Duration.ofMinutes(1), "healthy-owner"))
            .size());
  }

  private static boolean containsInjectedFailure(Throwable failure) {
    for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause())
      if (cause.toString().contains("injected-client-decoding")) return true;
    return false;
  }

  /**
   * Fault injection through the driver's supported CodecRegistrar extension, without private SDK
   * access.
   */
  private record FailingStringCodec(Codec<String> delegate, AtomicBoolean fired, boolean fatal)
      implements Codec<String>, CodecMetadata {
    @Override
    public Class<?> type() {
      return String.class;
    }

    @Override
    public Iterable<? extends PostgresTypeIdentifier> getDataTypes() {
      return ((CodecMetadata) delegate).getDataTypes();
    }

    @Override
    public boolean canDecode(int oid, Format format, Class<?> type) {
      return delegate.canDecode(oid, format, type);
    }

    @Override
    public boolean canEncode(Object value) {
      return false;
    }

    @Override
    public boolean canEncodeNull(Class<?> type) {
      return false;
    }

    @Override
    public @Nullable String decode(
        @Nullable ByteBuf buffer, int oid, Format format, Class<? extends String> type) {
      String value = delegate.decode(buffer, oid, format, type);
      if ("pipeline-fault".equals(value) && fired.compareAndSet(false, true)) {
        if (fatal) throw new AssertionError("injected-client-decoding");
        throw new IllegalStateException("injected-client-decoding");
      }
      return value;
    }

    @Override
    public EncodedParameter encode(Object value) {
      return delegate.encode(value);
    }

    @Override
    public EncodedParameter encode(Object value, int oid) {
      return delegate.encode(value, oid);
    }

    @Override
    public EncodedParameter encodeNull() {
      return delegate.encodeNull();
    }
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
                  "SELECT lease_until > statement_timestamp() AND lease_until <="
                      + " statement_timestamp() + interval '61 seconds' FROM axiz_outbox WHERE id='"
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
            "UPDATE axiz_outbox SET lease_until=statement_timestamp()-interval '1 second' WHERE"
                + " id='"
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
