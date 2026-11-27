package pe.axiz.spring.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.reactive.GenericReactiveTransaction;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import pe.axiz.adapter.outbox.jdbc.JdbcOutboxStore;
import pe.axiz.adapter.outbox.r2dbc.R2dbcAuditChainState;
import pe.axiz.adapter.outbox.r2dbc.R2dbcOutboxStore;
import pe.axiz.adapter.outbox.r2dbc.R2dbcOutboxWriter;
import pe.axiz.core.audit.AsyncAuditEventPublisher;
import pe.axiz.core.audit.AsyncStrictAuditPublisher;
import pe.axiz.core.audit.AttemptOutcome;
import pe.axiz.core.audit.AuditChainVerifier;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditEventJson;
import pe.axiz.core.audit.Auditable;
import pe.axiz.core.audit.QueuedAsyncAuditPublisher;
import pe.axiz.core.commons.TransactionOutcome;
import pe.axiz.core.json.JsonReader;
import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.kit.reactor.audit.ReactiveAuditChainState;
import pe.axiz.kit.reactor.outbox.ReactiveOutboxWriter;
import pe.axiz.quality.containers.TestContainerImages;
import pe.axiz.spring.foundation.ReactiveDispatchBoundary;
import pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap;
import pe.axiz.spring.r2dbc.DatabaseClientReactiveSql;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/** Transacciones R2DBC reales, callbacks nativos y una única conexión PostgreSQL de pool. */
@ExtendWith(BlockHoundExtension.class)
@Timeout(60)
class PostgresAuditBoundaryIT {
  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource source;
  private ConnectionPool pool;
  private DatabaseClient client;

  @BeforeAll
  static void start() {
    boolean docker = DockerClientFactory.instance().isDockerAvailable();
    if (Boolean.getBoolean("axiz.it.requireDocker"))
      Assertions.assertTrue(docker, "Docker is required");
    Assumptions.assumeTrue(docker, "Docker unavailable");
    postgres = TestContainerImages.postgres();
    postgres.start();
    source = new PGSimpleDataSource();
    source.setURL(postgres.getJdbcUrl());
    source.setUser(postgres.getUsername());
    source.setPassword(postgres.getPassword());
    ReactiveInfrastructureBootstrap.initialize();
  }

  @AfterAll
  static void stop() {
    if (postgres != null) postgres.stop();
  }

  @BeforeEach
  void schema() throws Exception {
    try (var connection = source.getConnection();
        var statement = connection.createStatement();
        var script = JdbcOutboxStore.class.getResourceAsStream("/axiz/sql/outbox-postgresql.sql")) {
      statement.execute("DROP TABLE IF EXISTS axiz_outbox");
      statement.execute("DROP TABLE IF EXISTS axiz_audit_chain");
      statement.execute("DROP TABLE IF EXISTS axiz_outbox_aggregate");
      statement.execute("DROP TABLE IF EXISTS audit_effects");
      assertNotNull(script);
      for (String sql :
          new String(script.readAllBytes(), StandardCharsets.UTF_8)
              .replaceAll("(?m)--.*", "")
              .split(";")) if (!sql.isBlank()) statement.execute(sql);
      statement.execute("CREATE TABLE audit_effects(id VARCHAR(100) PRIMARY KEY)");
    }
    var factory =
        ConnectionFactories.get(
            ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(ConnectionFactoryOptions.HOST, postgres.getHost())
                .option(ConnectionFactoryOptions.PORT, postgres.getMappedPort(5432))
                .option(ConnectionFactoryOptions.DATABASE, postgres.getDatabaseName())
                .option(ConnectionFactoryOptions.USER, postgres.getUsername())
                .option(ConnectionFactoryOptions.PASSWORD, postgres.getPassword())
                .build());
    pool =
        new ConnectionPool(
            ConnectionPoolConfiguration.builder(factory)
                .maxSize(1)
                .initialSize(0)
                .maxAcquireTime(Duration.ofSeconds(2))
                .build());
    client = DatabaseClient.create(pool);
  }

  @AfterEach
  void close() {
    pool.dispose();
  }

  @Configuration(proxyBeanMethods = false)
  @EnableTransactionManagement
  static class Config {
    @Bean
    Business business(DatabaseClient client) {
      return new Business(client);
    }
  }

  static class Business {
    private final DatabaseClient client;

    Business(DatabaseClient client) {
      this.client = client;
    }

    @Auditable(action = "success", resource = "test")
    @Transactional
    public Mono<String> success() {
      return client
          .sql("INSERT INTO audit_effects VALUES ('success')")
          .fetch()
          .rowsUpdated()
          .thenReturn("ok");
    }

    @Auditable(action = "failure", resource = "test")
    @Transactional
    public Mono<Void> failure() {
      return client
          .sql("INSERT INTO audit_effects VALUES ('failure')")
          .fetch()
          .rowsUpdated()
          .then(Mono.error(new IllegalStateException("original business failure")));
    }

    @Auditable(action = "no-transaction", resource = "test")
    public Mono<Void> noTransaction() {
      return Mono.error(new IllegalStateException("original without transaction"));
    }
  }

  private ReactiveWebApplicationContextRunner runner() {
    return runner(new R2dbcTransactionManager(pool));
  }

  private ReactiveWebApplicationContextRunner runner(ReactiveTransactionManager transactions) {
    var store =
        new R2dbcOutboxStore(
            new DatabaseClientReactiveSql(client), "axiz_outbox", Duration.ofSeconds(5));
    return new ReactiveWebApplicationContextRunner()
        .withPropertyValues("axiz.config.environment=test")
        .withConfiguration(
            AutoConfigurations.of(
                AxizAuditAutoConfiguration.class,
                AxizNativeAuditAutoConfiguration.class,
                AxizStrictAuditAutoConfiguration.class))
        .withUserConfiguration(Config.class)
        .withPropertyValues(
            "axiz.audit.mode=DURABLE",
            "axiz.outbox.relay.enabled=false",
            "axiz.audit.durable.outside-transaction=fail")
        .withBean(DatabaseClient.class, () -> client)
        .withBean(
            ReactiveAuditChainState.class,
            () ->
                new R2dbcAuditChainState(
                    new DatabaseClientReactiveSql(client),
                    pool,
                    "axiz_audit_chain",
                    Clock.systemUTC(),
                    Duration.ofSeconds(2)))
        .withBean(ReactiveTransactionManager.class, () -> transactions)
        .withBean(
            ReactiveOutboxWriter.class, () -> new R2dbcOutboxWriter(store, Clock.systemUTC()));
  }

  private int count(String table) throws Exception {
    try (var connection = source.getConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
      assertTrue(rows.next());
      return rows.getInt(1);
    }
  }

  private Map<String, @Nullable Object> audit() throws Exception {
    try (var connection = source.getConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT envelope FROM axiz_outbox")) {
      assertTrue(rows.next());
      var envelope = CloudEventJson.read(rows.getString(1));
      return JsonReader.parseObject(new String(envelope.data(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void successAndEvidenceShareTheCommitWithoutBlocking() {
    runner()
        .run(
            ctx -> {
              assertNull(ctx.getStartupFailure());
              StepVerifier.create(
                      Mono.defer(() -> ctx.getBean(Business.class).success())
                          .subscribeOn(Schedulers.parallel()))
                  .expectNext("ok")
                  .verifyComplete();
              assertEquals(1, count("audit_effects"));
              assertEquals(1, count("axiz_outbox"));
              assertEquals("COMMITTED", audit().get("transactionOutcome"));
            });
  }

  @Test
  void failureIsPersistedAfterRollbackAndConnectionRelease() {
    runner()
        .run(
            ctx -> {
              StepVerifier.create(
                      Mono.defer(() -> ctx.getBean(Business.class).failure())
                          .subscribeOn(Schedulers.parallel()))
                  .expectErrorMatches(
                      e ->
                          e instanceof IllegalStateException
                              && e.getMessage().equals("original business failure"))
                  .verify();
              assertEquals(0, count("audit_effects"));
              assertEquals(1, count("axiz_outbox"));
              assertEquals("ROLLED_BACK", audit().get("transactionOutcome"));
              assertEquals("UNKNOWN", audit().get("operationOutcome"));
              assertEquals(
                  0,
                  ((ReactiveTransactionalAuditPublisher) ctx.getBean(ReactiveAuditPublisher.class))
                      .notConfirmedCount());
            });
  }

  @Test
  void outerTransactionDefersFailureUntilTheMessageBoundaryExits() {
    runner()
        .run(
            ctx -> {
              var transaction =
                  TransactionalOperator.create(ctx.getBean(ReactiveTransactionManager.class));
              var business = ctx.getBean(Business.class);
              var boundary = ctx.getBean(ReactiveDispatchBoundary.class);
              Mono<Void> work =
                  business
                      .failure()
                      .onErrorResume(
                          original ->
                              client
                                  .sql("SELECT COUNT(*) FROM axiz_outbox")
                                  .map(row -> row.get(0, Long.class))
                                  .one()
                                  .doOnNext(n -> assertEquals(0L, n))
                                  .then(Mono.error(original)));
              StepVerifier.create(
                      boundary
                          .wrap(transaction.transactional(work))
                          .subscribeOn(Schedulers.parallel()))
                  .expectErrorMatches(e -> e.getMessage().equals("original business failure"))
                  .verify();
              assertEquals(0, count("audit_effects"));
              assertEquals(1, count("axiz_outbox"));
              assertEquals("ROLLED_BACK", audit().get("transactionOutcome"));
              assertEquals(
                  0,
                  ((ReactiveTransactionalAuditPublisher) ctx.getBean(ReactiveAuditPublisher.class))
                      .notConfirmedCount());
            });
  }

  @Test
  void failureWithoutBusinessTransactionStillHasIndependentDurableEvidence() {
    runner()
        .run(
            ctx -> {
              StepVerifier.create(
                      Mono.defer(() -> ctx.getBean(Business.class).noTransaction())
                          .subscribeOn(Schedulers.parallel()))
                  .expectErrorMessage("original without transaction")
                  .verify();
              assertEquals(1, count("axiz_outbox"));
              assertEquals("UNKNOWN", audit().get("transactionOutcome"));
            });
  }

  @Test
  void durableWriteErrorFallsBackWithoutReplacingTheOriginalBusinessError() throws Exception {
    try (var connection = source.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          "ALTER TABLE axiz_outbox ADD CONSTRAINT reject_audit CHECK (false) NOT VALID");
    }
    var delivered = new CompletableFuture<AuditEvent>();
    runner()
        .withBean(
            AsyncAuditSink.class,
            () ->
                new AsyncAuditSink(
                    "test",
                    event -> {
                      delivered.complete(event);
                      return CompletableFuture.completedFuture(null);
                    }))
        .run(
            ctx -> {
              StepVerifier.create(
                      Mono.defer(() -> ctx.getBean(Business.class).failure())
                          .subscribeOn(Schedulers.parallel()))
                  .expectErrorMessage("original business failure")
                  .verify();
              var fallback = delivered.get(5, TimeUnit.SECONDS);
              assertEquals("NOT_CONFIRMED", fallback.persistence());
              assertEquals("ROLLED_BACK", fallback.transactionOutcome().name());
              assertEquals(
                  1,
                  ((ReactiveTransactionalAuditPublisher) ctx.getBean(ReactiveAuditPublisher.class))
                      .notConfirmedCount());
              assertEquals(0, count("audit_effects"));
              assertEquals(0, count("axiz_outbox"));
              var queue = (QueuedAsyncAuditPublisher) ctx.getBean(AsyncAuditEventPublisher.class);
              assertEquals(0, queue.lostCount());
              var registry = new SimpleMeterRegistry();
              ctx.getBeansOfType(MeterBinder.class)
                  .values()
                  .forEach(binder -> binder.bindTo(registry));
              assertEquals(
                  1.0,
                  registry.get("axiz.audit.persistence.not.confirmed").functionCounter().count());
            });
  }

  @Test
  void lostCommitAcknowledgementAndTimeoutKeepBothNativeAttempts() {
    for (boolean timeout : List.of(false, true)) {
      var delivered = new CompletableFuture<AuditEvent>();
      var transactions =
          new R2dbcTransactionManager(pool) {
            @Override
            protected Mono<Void> doCommit(
                TransactionSynchronizationManager manager, GenericReactiveTransaction status) {
              Mono<Void> acknowledgement =
                  timeout
                      ? Mono.delay(Duration.ofSeconds(1)).then()
                      : Mono.error(new TransactionSystemException("commit acknowledgement lost"));
              return super.doCommit(manager, status).then(acknowledgement);
            }
          };
      runner(transactions)
          .withPropertyValues("axiz.audit.durable.failure-timeout=150ms")
          .withBean(
              AsyncAuditSink.class,
              () ->
                  new AsyncAuditSink(
                      "capture",
                      event -> {
                        delivered.complete(event);
                        return CompletableFuture.completedFuture(null);
                      }))
          .run(
              ctx -> {
                StepVerifier.create(
                        Mono.defer(() -> ctx.getBean(Business.class).noTransaction())
                            .subscribeOn(Schedulers.parallel()))
                    .expectErrorMessage("original without transaction")
                    .verify(Duration.ofSeconds(3));
                AuditEvent fallback = delivered.get(3, TimeUnit.SECONDS);
                assertEquals("UNCERTAIN", fallback.persistence());
                assertEquals(
                    AttemptOutcome.UNKNOWN, fallback.integrity().previousAttempt().outcome());
                assertEquals(
                    AuditChainVerifier.IntegrityStatus.INTACT,
                    AuditChainVerifier.verify(List.of(fallback), null, null).integrity());
                try (var connection = source.getConnection()) {
                  var ref = fallback.integrity().previousAttempt();
                  // Se identifica el intento real decodificando; el id del hecho no es, de forma
                  // deliberada, el id del outbox.
                  try (var all = connection.createStatement();
                      var rows = all.executeQuery("SELECT envelope FROM axiz_outbox")) {
                    boolean found = false;
                    while (rows.next()) {
                      var primary =
                          AuditEventJson.read(
                              new String(
                                  CloudEventJson.read(rows.getString(1)).data(),
                                  StandardCharsets.UTF_8));
                      if (primary.integrity().hash().equals(ref.hash())
                          && primary.id().equals(fallback.id())) {
                        found = true;
                        assertEquals(
                            AuditChainVerifier.IntegrityStatus.INTACT,
                            AuditChainVerifier.verify(List.of(primary), null, null).integrity());
                      }
                    }
                    assertTrue(found, "actual commit survives the lost acknowledgement or timeout");
                  }
                }
              });
    }
  }

  @Test
  void nativeStrictOutboxConfirmsCommitAndFailsClosedOnAnUnwritableStore() throws Exception {
    runner()
        .withPropertyValues(
            "axiz.audit.mode=ASYNC",
            "axiz.audit.strict.enabled=true",
            "axiz.audit.strict.mode=DURABLE")
        .run(
            ctx -> {
              assertNull(ctx.getStartupFailure());
              var strict = ctx.getBean(AsyncStrictAuditPublisher.class);
              StepVerifier.create(
                      Mono.fromCompletionStage(() -> strict.publish(strictEvent()))
                          .subscribeOn(Schedulers.parallel()))
                  .expectComplete()
                  .verify(Duration.ofSeconds(5));
              assertEquals(1, count("axiz_outbox"));
              try (var connection = source.getConnection();
                  var statement = connection.createStatement()) {
                statement.execute(
                    "ALTER TABLE axiz_outbox ADD CONSTRAINT reject_strict CHECK (false) NOT VALID");
              }
              StepVerifier.create(
                      Mono.fromCompletionStage(() -> strict.publish(strictEvent()))
                          .subscribeOn(Schedulers.parallel()))
                  .expectError()
                  .verify(Duration.ofSeconds(5));
              assertEquals(1, count("axiz_outbox"));
            });
  }

  @Test
  void nativeStrictLostAcknowledgementDoesNotReturnSuccessOrEraseTheCommittedRecord() {
    var transactions =
        new R2dbcTransactionManager(pool) {
          @Override
          protected Mono<Void> doCommit(
              TransactionSynchronizationManager manager, GenericReactiveTransaction status) {
            return super.doCommit(manager, status)
                .then(Mono.error(new TransactionSystemException("ack lost")));
          }
        };
    runner(transactions)
        .withPropertyValues(
            "axiz.audit.mode=ASYNC",
            "axiz.audit.strict.enabled=true",
            "axiz.audit.strict.mode=DURABLE")
        .run(
            ctx -> {
              assertNull(ctx.getStartupFailure());
              StepVerifier.create(
                      Mono.fromCompletionStage(
                              () ->
                                  ctx.getBean(AsyncStrictAuditPublisher.class)
                                      .publish(strictEvent()))
                          .subscribeOn(Schedulers.parallel()))
                  .expectError()
                  .verify(Duration.ofSeconds(5));
              assertEquals(1, count("axiz_outbox"));
            });
  }

  private static AuditEvent strictEvent() {
    return AuditEvent.builder()
        .id("strict-ack")
        .occurredAt(Instant.EPOCH)
        .service("vault")
        .action("lookup")
        .resourceType("token")
        .resourceId(null)
        .actor(AuditEvent.Actor.system())
        .outcome(AuditEvent.Outcome.SUCCESS)
        .errorCode(null)
        .correlationId(null)
        .channel(null)
        .tenant(null)
        .details(Map.of())
        .transactionOutcome(TransactionOutcome.UNKNOWN)
        .operationOutcome(TransactionOutcome.UNKNOWN)
        .persistence(null)
        .integrity(null)
        .detailsSanitized(0)
        .build();
  }

  @Test
  void saturatedNativePoolHonorsTheAuditDeadlineAndPreservesTheBusinessError() {
    var delivered = new CompletableFuture<AuditEvent>();
    runner()
        .withPropertyValues("axiz.audit.durable.failure-timeout=30ms")
        .withBean(
            AsyncAuditSink.class,
            () ->
                new AsyncAuditSink(
                    "test",
                    event -> {
                      delivered.complete(event);
                      return CompletableFuture.completedFuture(null);
                    }))
        .run(
            ctx -> {
              var held = Mono.from(pool.create()).block(Duration.ofSeconds(5));
              assertNotNull(held);
              try {
                StepVerifier.create(
                        Mono.defer(() -> ctx.getBean(Business.class).noTransaction())
                            .subscribeOn(Schedulers.parallel()))
                    .expectErrorMessage("original without transaction")
                    .verify(Duration.ofSeconds(2));
                assertEquals("NOT_CONFIRMED", delivered.get(5, TimeUnit.SECONDS).persistence());
                assertEquals(
                    1,
                    ((ReactiveTransactionalAuditPublisher)
                            ctx.getBean(ReactiveAuditPublisher.class))
                        .notConfirmedCount());
              } finally {
                Mono.from(held.close()).block(Duration.ofSeconds(5));
              }
              assertEquals(0, count("axiz_outbox"));
            });
  }
}
