package pe.axiz.quality.stores;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.observation.ObservationRegistry;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import pe.axiz.adapter.outbox.jdbc.JdbcOutboxStore;
import pe.axiz.adapter.outbox.r2dbc.R2dbcOutboxStore;
import pe.axiz.adapter.outbox.r2dbc.ReactiveOutboxWriter;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditEvent.Outcome;
import pe.axiz.core.audit.Auditable;
import pe.axiz.core.domain.DomainEvent;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.PayloadCodec;
import pe.axiz.core.sensitive.ObjectMasker;
import pe.axiz.kit.r2dbc.ReactiveSql;
import pe.axiz.kit.r2dbc.SqlParams;
import pe.axiz.spring.audit.AuditOutsideTransaction;
import pe.axiz.spring.audit.AxizAuditAutoConfiguration;
import pe.axiz.spring.audit.AxizNativeAuditAutoConfiguration;
import pe.axiz.spring.audit.ReactiveAuditTrail;
import pe.axiz.spring.audit.ReactiveTransactionalAuditPublisher;
import pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap;
import pe.axiz.spring.outbox.ReactiveTransactionalEvents;
import pe.axiz.spring.r2dbc.DatabaseClientReactiveSql;
import pe.axiz.spring.test.BlockHoundExtension;
import pe.axiz.structure.hexagonal.ReactiveDomainEventSink;
import pe.axiz.structure.hexagonal.ReactiveUseCase;
import pe.axiz.structure.hexagonal.annotation.UseCase;
import pe.axiz.structure.hexagonal.spring.AxizHexagonalAutoConfiguration;
import pe.axiz.structure.hexagonal.spring.AxizReactiveHexagonalAutoConfiguration;
import pe.axiz.structure.hexagonal.spring.ReactiveOutboxDomainEventSink;
import pe.axiz.structure.hexagonal.spring.ReactiveUseCaseInterceptor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * Real PostgreSQL proves business and outbox commit or roll back together across thread changes.
 */
@ExtendWith(BlockHoundExtension.class)
class PostgresReactiveUseCaseIT {
  private static final Duration T = Duration.ofSeconds(10);
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse(Docker.POSTGRES))
          .withDatabaseName("app")
          .withUsername("app")
          .withPassword("local-only");
  private static ConnectionFactory connections;
  private static ReactiveSql sql;
  private static ReactiveDomainEventSink sink;

  @BeforeAll
  static void start() throws Exception {
    ReactiveInfrastructureBootstrap.initialize();
    Docker.require();
    POSTGRES.start();
    connections =
        ConnectionFactories.get(
            ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(ConnectionFactoryOptions.HOST, POSTGRES.getHost())
                .option(ConnectionFactoryOptions.PORT, POSTGRES.getMappedPort(5432))
                .option(ConnectionFactoryOptions.DATABASE, POSTGRES.getDatabaseName())
                .option(ConnectionFactoryOptions.USER, POSTGRES.getUsername())
                .option(ConnectionFactoryOptions.PASSWORD, POSTGRES.getPassword())
                .build());
    sql = new DatabaseClientReactiveSql(DatabaseClient.create(connections));
    sql.update("CREATE TABLE business_changes (id VARCHAR(64) PRIMARY KEY)", SqlParams.of())
        .block(T);
    try (var in =
        Objects.requireNonNull(
            JdbcOutboxStore.class.getResourceAsStream("/axiz/sql/outbox-postgresql.sql"))) {
      for (String statement : new String(in.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
        if (!statement.replaceAll("--.*", "").isBlank())
          sql.update(statement, SqlParams.of()).block(T);
      }
    }
    PayloadCodec codec =
        new PayloadCodec() {
          @Override
          public String contentType() {
            return "text/plain";
          }

          @Override
          public byte[] encode(Object value) {
            return value.toString().getBytes(StandardCharsets.UTF_8);
          }

          @Override
          public <V> V decode(byte[] bytes, Class<V> type) {
            return type.cast(new String(bytes, StandardCharsets.UTF_8));
          }
        };
    var events =
        new ReactiveTransactionalEvents(
            new EventFactory(
                "urn:test:original", "", codec, IdGenerator.uuidV7(), Clock.systemUTC()),
            new ReactiveOutboxWriter(
                new R2dbcOutboxStore(sql, "axiz_outbox", T), Clock.systemUTC()));
    sink = new ReactiveOutboxDomainEventSink(events, "payments", Map.of(), ".v1");
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  record Changed(String eventId, Instant occurredAt, String aggregateId) implements DomainEvent {}

  @UseCase("reactive-change")
  public static class Change implements ReactiveUseCase<String, String> {
    final AtomicInteger calls = new AtomicInteger();
    final Sinks.One<Void> written = Sinks.one();

    @Override
    public Mono<String> execute(String mode) {
      String id = mode + "-" + calls.incrementAndGet();
      return sql.update(
              "INSERT INTO business_changes (id) VALUES (:id)", SqlParams.of().bind("id", id))
          .publishOn(Schedulers.parallel())
          .then(Mono.from(sink.publish(List.of(new Changed("event-" + id, Instant.EPOCH, id)))))
          .doOnSuccess(ignored -> written.tryEmitEmpty())
          .then(
              Mono.defer(
                  () ->
                      switch (mode) {
                        case "error" -> Mono.error(new IllegalStateException("business failed"));
                        case "cancel" -> Mono.never();
                        default -> Mono.just(id);
                      }));
    }
  }

  private static Change proxy(Change target) {
    ProxyFactory factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAdvice(
        new ReactiveUseCaseInterceptor(
            () -> new R2dbcTransactionManager(connections), ObservationRegistry.NOOP));
    return (Change) factory.getProxy();
  }

  @Test
  void startsOnlyAtSubscriptionAndCommitsBothWrites() {
    Change target = new Change();
    Mono<String> work = proxy(target).execute("commit");
    assertEquals(0, target.calls.get());
    StepVerifier.create(work.subscribeOn(Schedulers.parallel()))
        .expectNext("commit-1")
        .verifyComplete();
    StepVerifier.create(work.subscribeOn(Schedulers.parallel()))
        .expectNext("commit-2")
        .verifyComplete();
    assertEquals(2, target.calls.get());
    assertEquals(2, count("business_changes", "commit-%"));
    assertEquals(2, count("axiz_outbox", "event-commit-%"));
  }

  @Test
  void businessFailureRollsBackBothWrites() {
    StepVerifier.create(proxy(new Change()).execute("error").subscribeOn(Schedulers.parallel()))
        .expectErrorMessage("business failed")
        .verify(T);
    assertEquals(0, count("business_changes", "error-%"));
    assertEquals(0, count("axiz_outbox", "event-error-%"));
  }

  @Test
  void cancellationRollsBackBothWrites() {
    Change target = new Change();
    StepVerifier.create(proxy(target).execute("cancel").subscribeOn(Schedulers.parallel()))
        .then(() -> target.written.asMono().block(T))
        .thenCancel()
        .verify(T);
    // A fresh query waits for asynchronous rollback without blocking an I/O worker.
    Mono.defer(
            () ->
                sql.query(
                        "SELECT count(*) AS n FROM pg_stat_activity "
                            + "WHERE datname='app' AND state='idle in transaction'",
                        SqlParams.of(),
                        row -> row.require("n", Long.class))
                    .single())
        .repeatWhen(repeats -> repeats.delayElements(Duration.ofMillis(20)))
        .filter(n -> n == 0)
        .next()
        .block(T);
    assertEquals(0, count("business_changes", "cancel-%"));
    assertEquals(0, count("axiz_outbox", "event-cancel-%"));
  }

  @Test
  void cannotEnqueueOutsideTransaction() {
    StepVerifier.create(
            Mono.from(sink.publish(List.of(new Changed("outside", Instant.EPOCH, "outside")))))
        .expectErrorMatches(
            error ->
                error instanceof IllegalStateException
                    && error.getMessage().contains("transaction"))
        .verify(T);
    assertEquals(0, count("axiz_outbox", "outside"));
  }

  @Test
  void reconnectsUnderScramWithoutBlockingAfterEntropyBufferWouldExpire() {
    StepVerifier.create(
            Mono.defer(
                    () ->
                        Mono.usingWhen(
                            Mono.from(connections.create()),
                            connection ->
                                Mono.from(connection.createStatement("SELECT 1").execute())
                                    .flatMap(result -> Mono.from(result.getRowsUpdated()))
                                    .then(),
                            connection -> Mono.from(connection.close())))
                .then(Mono.delay(Duration.ofMillis(150)))
                .repeat(8)
                .subscribeOn(Schedulers.parallel()))
        .expectNextCount(9)
        .verifyComplete();
  }

  @Test
  void durableAuditCommitsWithBusinessDataAndRollsBackDeliveryFailure() {
    var fallback = new CopyOnWriteArrayList<AuditEvent>();
    var publisher =
        new ReactiveTransactionalAuditPublisher(
            new ReactiveOutboxWriter(
                new R2dbcOutboxStore(sql, "axiz_outbox", T), Clock.systemUTC()),
            "audit",
            event -> Mono.fromRunnable(() -> fallback.add(event)),
            AuditOutsideTransaction.FAIL);
    var ids = new AtomicInteger();
    var trail =
        new ReactiveAuditTrail(
            "accounts",
            publisher,
            ObjectMasker.defaults(),
            () -> "audit-proof-" + ids.incrementAndGet(),
            Clock.systemUTC());
    var transaction = TransactionalOperator.create(new R2dbcTransactionManager(connections));
    Mono<String> committed =
        Mono.defer(
            () ->
                trail
                    .record("create", "account")
                    .resourceId("proof")
                    .detail("request", Map.of("password", "hidden", "amount", 10))
                    .around(
                        sql.update(
                                "INSERT INTO business_changes (id) VALUES ('audit-committed')",
                                SqlParams.of())
                            .publishOn(Schedulers.parallel())
                            .thenReturn("created")));
    StepVerifier.create(transaction.transactional(committed).subscribeOn(Schedulers.parallel()))
        .expectNext("created")
        .expectComplete()
        .verify(T);
    assertEquals(1, count("business_changes", "audit-committed"));
    assertEquals(1, count("axiz_outbox", "audit-proof-1"));
    String json =
        sql.query(
                "SELECT envelope FROM axiz_outbox WHERE id='audit-proof-1'",
                SqlParams.of(),
                row -> row.require("envelope", String.class))
            .single()
            .block(T);
    org.junit.jupiter.api.Assertions.assertFalse(Objects.requireNonNull(json).contains("hidden"));
    Mono<String> aborted =
        Mono.defer(
            () ->
                trail
                    .record("create", "account")
                    .around(
                        sql.update(
                                "INSERT INTO business_changes (id) VALUES ('audit-aborted')",
                                SqlParams.of())
                            .then(Mono.error(new IllegalStateException("business failed")))));
    StepVerifier.create(transaction.transactional(aborted).subscribeOn(Schedulers.parallel()))
        .expectErrorMessage("business failed")
        .verify(T);
    assertEquals(0, count("business_changes", "audit-aborted"));
    assertEquals(0, count("axiz_outbox", "audit-proof-2"));
    assertEquals(1, fallback.size());
    assertEquals(Outcome.FAILURE, fallback.getFirst().outcome());

    var rejected =
        new ReactiveTransactionalAuditPublisher(
            new ReactiveOutboxWriter(
                new R2dbcOutboxStore(sql, "missing_audit_table", T), Clock.systemUTC()),
            "audit",
            event -> Mono.empty(),
            AuditOutsideTransaction.FAIL);
    var unavailable =
        new ReactiveAuditTrail(
            "accounts", rejected, ObjectMasker.defaults(), IdGenerator.uuidV7(), Clock.systemUTC());
    Mono<String> noEvidence =
        Mono.defer(
            () ->
                unavailable
                    .record("create", "account")
                    .around(
                        sql.update(
                                "INSERT INTO business_changes (id) VALUES ('audit-no-evidence')",
                                SqlParams.of())
                            .thenReturn("created")));
    StepVerifier.create(transaction.transactional(noEvidence).subscribeOn(Schedulers.parallel()))
        .expectError()
        .verify(T);
    assertEquals(0, count("business_changes", "audit-no-evidence"));
  }

  @UseCase("audited-change")
  public static class AuditedChange implements ReactiveUseCase<String, String> {
    @Auditable(action = "account.change", resource = "account")
    public Mono<String> execute(String id) {
      return sql.update(
              "INSERT INTO business_changes (id) VALUES (:id)", SqlParams.of().bind("id", id))
          .thenReturn(id);
    }
  }

  @Test
  void nativeAuditAdviceIsInsideTheHexagonalUseCaseTransaction() {
    var writer =
        new ReactiveOutboxWriter(new R2dbcOutboxStore(sql, "axiz_outbox", T), Clock.systemUTC());
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizAuditAutoConfiguration.class,
                AxizNativeAuditAutoConfiguration.class,
                AxizHexagonalAutoConfiguration.class,
                AxizReactiveHexagonalAutoConfiguration.class))
        .withBean(ReactiveOutboxWriter.class, () -> writer)
        .withBean(ReactiveTransactionManager.class, () -> new R2dbcTransactionManager(connections))
        .withPropertyValues(
            "axiz.audit.durable.enabled=true", "axiz.audit.durable.outside-transaction=fail")
        .run(
            ctx -> {
              org.assertj.core.api.Assertions.assertThat(ctx).hasNotFailed();
              var factory = new ProxyFactory(new AuditedChange());
              factory.setProxyTargetClass(true);
              var advisors =
                  new ArrayList<>(
                      List.of(
                          ctx.getBean("axizReactiveAuditableAdvisor", Advisor.class),
                          ctx.getBean("axizReactiveUseCaseAdvisor", Advisor.class),
                          ctx.getBean("axizReactiveAuditSuccessAdvisor", Advisor.class)));
              AnnotationAwareOrderComparator.sort(advisors);
              advisors.forEach(factory::addAdvisor);
              var useCase = (AuditedChange) factory.getProxy();
              StepVerifier.create(
                      useCase.execute("audit-through-advice").subscribeOn(Schedulers.parallel()))
                  .expectNext("audit-through-advice")
                  .expectComplete()
                  .verify(T);
              assertEquals(1, count("business_changes", "audit-through-advice"));
              Long evidence =
                  sql.query(
                          "SELECT count(*) AS n FROM axiz_outbox WHERE envelope LIKE :pattern",
                          SqlParams.of().bind("pattern", "%account.change%"),
                          row -> row.require("n", Long.class))
                      .single()
                      .block(T);
              assertEquals(1L, evidence);
            });
  }

  private static long count(String table, String pattern) {
    return Objects.requireNonNull(
        sql.query(
                "SELECT count(*) AS n FROM " + table + " WHERE id LIKE :id",
                SqlParams.of().bind("id", pattern),
                row -> row.require("n", Long.class))
            .single()
            .block(T));
  }
}
