package pe.axiz.adapter.outbox.r2dbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import pe.axiz.core.audit.AuditChainVerifier;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditEventJson;
import pe.axiz.core.audit.AuditIntegrity;
import pe.axiz.core.audit.AuditSealCalculator;
import pe.axiz.core.audit.AuditSealer;
import pe.axiz.core.commons.TransactionOutcome;
import pe.axiz.core.json.JsonWriter;
import pe.axiz.kit.r2dbc.R2dbcSql;
import pe.axiz.kit.r2dbc.SqlParams;
import pe.axiz.quality.containers.TestContainerImages;
import reactor.blockhound.BlockHound;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Real PostgreSQL locks, transaction rollback, committed checkpoints and native execution. */
@Timeout(120)
class PostgresAuditChainIT {
  private static PostgreSQLContainer postgres;
  private static ConnectionFactory connections;
  private static R2dbcSql sql;
  private static R2dbcAuditChainState state;
  private static final AuditSealCalculator CALCULATOR =
      new AuditSealCalculator(AuditSealer.Mode.HASH, null, null);

  @BeforeAll
  static void start() throws Exception {
    boolean available = DockerClientFactory.instance().isDockerAvailable();
    if (Boolean.getBoolean("axiz.it.requireDocker")) assertTrue(available, "Docker is required");
    Assumptions.assumeTrue(available, "Docker unavailable");
    postgres = TestContainerImages.postgres();
    postgres.start();
    try (var connection =
            java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement();
        var schema =
            Objects.requireNonNull(
                PostgresAuditChainIT.class.getResourceAsStream(
                    "/axiz/sql/outbox-postgresql.sql"))) {
      statement.execute(new String(schema.readAllBytes(), StandardCharsets.UTF_8));
    }
    connections =
        ConnectionFactories.get(
            ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(ConnectionFactoryOptions.HOST, postgres.getHost())
                .option(ConnectionFactoryOptions.PORT, postgres.getMappedPort(5432))
                .option(ConnectionFactoryOptions.DATABASE, postgres.getDatabaseName())
                .option(ConnectionFactoryOptions.USER, postgres.getUsername())
                .option(ConnectionFactoryOptions.PASSWORD, postgres.getPassword())
                .option(ConnectionFactoryOptions.CONNECT_TIMEOUT, Duration.ofSeconds(5))
                .build());
    sql = new R2dbcSql(connections);
    state =
        new R2dbcAuditChainState(
            sql, connections, "axiz_audit_chain", Clock.systemUTC(), Duration.ofSeconds(5));
    CALCULATOR.next(event("warmup"), AuditIntegrity.initial("warmup"), null);
    BlockHound.builder()
        .loadIntegrations()
        .allowBlockingCallsInside("io.netty.buffer.AdaptivePoolingAllocator$Magazine", "free")
        .install();
  }

  @AfterAll
  static void stop() {
    if (postgres != null) postgres.stop();
  }

  private static AuditEvent event(String id) {
    return AuditEvent.builder()
        .id(id)
        .occurredAt(Instant.EPOCH)
        .service("svc")
        .action("change")
        .resourceType("account")
        .resourceId("1")
        .actor(AuditEvent.Actor.system())
        .outcome(AuditEvent.Outcome.SUCCESS)
        .errorCode(null)
        .correlationId(null)
        .channel(null)
        .tenant(null)
        .details(Map.of("amount", 0.1f))
        .transactionOutcome(TransactionOutcome.COMMITTED)
        .operationOutcome(TransactionOutcome.COMMITTED)
        .persistence(null)
        .integrity(null)
        .detailsSanitized(0)
        .build();
  }

  private static Mono<AuditEvent> seal(String chain, AuditEvent event) {
    return state
        .advance(chain, head -> CALCULATOR.next(event, head, null))
        .map(event::withIntegrity);
  }

  @Test
  void activeTransactionAndItsResourceAreRequired() {
    assertFalse(sql.isTransactionActive().block());
    assertThrows(IllegalStateException.class, () -> seal("outside", event("1")).block());
    assertEquals(0, state.checkpoint("outside").block().sequence());
    assertTrue(sql.inTransaction(ignored -> sql.isTransactionActive()).block());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new R2dbcAuditChainState(
                sql,
                new ConnectionFactory() {
                  @Override
                  public org.reactivestreams.Publisher<? extends io.r2dbc.spi.Connection> create() {
                    return connections.create();
                  }

                  @Override
                  public io.r2dbc.spi.ConnectionFactoryMetadata getMetadata() {
                    return connections.getMetadata();
                  }
                },
                "axiz_audit_chain",
                Clock.systemUTC(),
                Duration.ofSeconds(5)));
  }

  @Test
  void rollbackAndUncommittedHeadsCannotBecomeTrustedCheckpoints() {
    String chain = UUID.randomUUID().toString();
    var attempted = new AtomicReference<AuditEvent>();
    assertThrows(
        IllegalStateException.class,
        () ->
            sql.inTransaction(
                    ignored ->
                        seal(chain, event("rollback"))
                            .doOnNext(attempted::set)
                            .flatMap(signed -> state.checkpoint(chain))
                            .doOnNext(checkpoint -> assertEquals(0, checkpoint.sequence()))
                            .then(Mono.error(new IllegalStateException("rollback"))))
                .subscribeOn(Schedulers.parallel())
                .block(Duration.ofSeconds(10)));
    assertEquals(0, state.checkpoint(chain).block().sequence());
    AuditEvent committed =
        sql.inTransaction(ignored -> seal(chain, event("commit")))
            .subscribeOn(Schedulers.parallel())
            .block(Duration.ofSeconds(10));
    assertEquals(1, committed.integrity().sequence());
    assertNotEquals(attempted.get().integrity().hash(), committed.integrity().hash());
    var result =
        AuditChainVerifier.verify(List.of(committed), null, state.checkpoint(chain).block());
    assertEquals(AuditChainVerifier.IntegrityStatus.INTACT, result.integrity());
    assertEquals(AuditChainVerifier.Completeness.COMPLETE_UP_TO, result.completeness());
  }

  @Test
  void concurrentTransactionsPreserveAllCommittedRecordsAndExposeSerializationCost(
      TestReporter reporter) throws Exception {
    String chain = UUID.randomUUID().toString();
    var latencies = new ConcurrentLinkedQueue<Long>();
    long start = System.nanoTime();
    List<AuditEvent> committed =
        Flux.range(0, 500)
            .flatMap(
                index -> {
                  long before = System.nanoTime();
                  return sql.inTransaction(
                          ignored ->
                              seal(chain, event("event-" + index))
                                  .flatMap(
                                      signed -> {
                                        // A simplified record write uses the same resource and
                                        // transaction as the chain head.
                                        return sql.update(
                                                "INSERT INTO axiz_outbox"
                                                    + " (id,destination,aggregate_type,"
                                                    + "aggregate_id,event_type,envelope,"
                                                    + "status,attempts,created_at,next_attempt_at,aggregate_seq)"
                                                    + " VALUES"
                                                    + " (:id,'audit','audit',:chain,'audit',:payload,"
                                                    + "'PENDING',0,:at,:at,:sequence)",
                                                SqlParams.of()
                                                    .bind("id", signed.deliveryId())
                                                    .bind("chain", chain)
                                                    .bind("payload", AuditEventJson.write(signed))
                                                    .bind("at", Instant.now())
                                                    .bind(
                                                        "sequence", signed.integrity().sequence()))
                                            .thenReturn(signed);
                                      }))
                      .doOnSuccess(ignored -> latencies.add(System.nanoTime() - before));
                },
                8)
            .subscribeOn(Schedulers.parallel())
            .collectList()
            .block(Duration.ofSeconds(60));
    var checkpoint = state.checkpoint(chain).block();
    assertEquals(500, checkpoint.sequence());
    assertEquals(
        AuditChainVerifier.IntegrityStatus.INTACT,
        AuditChainVerifier.verify(committed, null, checkpoint).integrity());
    var stored =
        sql.query(
                "SELECT envelope FROM axiz_outbox WHERE aggregate_id=:chain",
                SqlParams.of().bind("chain", chain),
                row -> AuditEventJson.read(row.require("envelope", String.class)))
            .collectList()
            .block();
    assertEquals(
        AuditChainVerifier.Completeness.COMPLETE_UP_TO,
        AuditChainVerifier.verify(stored, null, checkpoint).completeness());
    var ordered = latencies.stream().sorted().toList();
    var measurement =
        Map.of(
            "transactions",
            "500",
            "concurrency",
            "8",
            "elapsedMs",
            Long.toString((System.nanoTime() - start) / 1_000_000),
            "p95Ms",
            Long.toString(ordered.get(474) / 1_000_000));
    reporter.publishEntry(measurement);
    Files.writeString(
        Path.of("target", "audit-chain-load.json"),
        JsonWriter.writeCanonical(measurement),
        StandardCharsets.UTF_8);
  }
}
