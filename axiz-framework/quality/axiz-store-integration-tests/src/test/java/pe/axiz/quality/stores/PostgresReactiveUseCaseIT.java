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
import pe.axiz.adapter.outbox.jdbc.JdbcOutboxStore;
import pe.axiz.adapter.outbox.r2dbc.R2dbcAuditChainState;
import pe.axiz.adapter.outbox.r2dbc.R2dbcOutboxStore;
import pe.axiz.adapter.outbox.r2dbc.R2dbcOutboxWriter;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditEvent.Outcome;
import pe.axiz.core.audit.AuditEventJson;
import pe.axiz.core.audit.AuditSealCalculator;
import pe.axiz.core.audit.AuditSealer;
import pe.axiz.core.audit.Auditable;
import pe.axiz.core.audit.InMemoryAuditChainState;
import pe.axiz.core.domain.DomainEvent;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.PayloadCodec;
import pe.axiz.core.sensitive.ObjectMasker;
import pe.axiz.kit.r2dbc.R2dbcSql;
import pe.axiz.kit.r2dbc.ReactiveSql;
import pe.axiz.kit.r2dbc.SqlParams;
import pe.axiz.kit.reactor.audit.ReactiveAuditChainState;
import pe.axiz.kit.reactor.outbox.ReactiveOutboxWriter;
import pe.axiz.quality.containers.TestContainerImages;
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
 * PostgreSQL real prueba que el negocio y el outbox se confirman o hacen rollback juntos a través
 * de cambios de hilo.
 */
@ExtendWith(BlockHoundExtension.class)
class PostgresReactiveUseCaseIT {
  private static final Duration T = Duration.ofSeconds(10);
  private static PostgreSQLContainer postgres;
  private static ConnectionFactory connections;
  private static ReactiveSql sql;
  private static ReactiveDomainEventSink sink;

  @BeforeAll
  static void start() throws Exception {
    ReactiveInfrastructureBootstrap.initialize();
    Docker.require();
    postgres =
        TestContainerImages.postgres()
            .withDatabaseName("app")
            .withUsername("app")
            .withPassword("local-only");
    postgres.start();
    connections =
        ConnectionFactories.get(
            ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(ConnectionFactoryOptions.HOST, postgres.getHost())
                .option(ConnectionFactoryOptions.PORT, postgres.getMappedPort(5432))
                .option(ConnectionFactoryOptions.DATABASE, postgres.getDatabaseName())
                .option(ConnectionFactoryOptions.USER, postgres.getUsername())
                .option(ConnectionFactoryOptions.PASSWORD, postgres.getPassword())
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
            new R2dbcOutboxWriter(new R2dbcOutboxStore(sql, "axiz_outbox", T), Clock.systemUTC()));
    sink = new ReactiveOutboxDomainEventSink(events, "payments", Map.of(), ".v1");
  }

  @AfterAll
  static void stop() {
    if (postgres != null) postgres.stop();
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
            () -> new R2dbcTransactionManager(connections), () -> ObservationRegistry.NOOP));
    return (Change) factory.getProxy();
  }

  @Test
  void nativeNamedBindingsPreservePostgresLiteralAndCommentSyntax() {
    var nativeSql = new R2dbcSql(connections);
    String statement =
        "SELECT $tag$:hidden 'quote' $tag$ AS dollar, "
            + "'firstit''s :hidden' AS escaped, "
            + "/* outer /* inner */ :ignored */ :actual::text AS bound";
    var result =
        nativeSql
            .queryOne(
                statement,
                SqlParams.of().bind("actual", "untrusted ':value' $$"),
                row ->
                    List.of(
                        row.require("dollar", String.class),
                        row.require("escaped", String.class),
                        row.require("bound", String.class)))
            .block(T);
    assertEquals(List.of(":hidden 'quote' ", "firstit's :hidden", "untrusted ':value' $$"), result);
  }

  @Test
  void driverRejectsBackslashQuotedLiteralsInsteadOfExecutingMisparsedSql() {
    // PostgreSQL acepta esta gramática y NamedSql la preserva. El lexer propio del driver R2DBC
    // fijado no reconoce las comillas escapadas con barra invertida. Se mantiene la limitación como
    // algo ejecutable; los valores de la aplicación deben usar parámetros de binding, nunca
    // reescritura de literales SQL.
    var sql = new R2dbcSql(connections);
    for (String literal :
        List.of("E'firstit\\'s :hidden'", "E'first' -- continuation\n'it\\'s :hidden'")) {
      StepVerifier.create(
              sql.queryOne(
                  "SELECT " + literal + ", :actual::text",
                  SqlParams.of().bind("actual", "bound"),
                  row -> row.require("actual", String.class)))
          .expectErrorMatches(
              failure ->
                  failure instanceof IllegalArgumentException
                      && failure.getMessage().startsWith("Sql cannot be parsed: unclosed quote"))
          .verify(T);
    }
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
    // Una consulta nueva espera el rollback asíncrono sin bloquear un worker de E/S.
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
        ReactiveTransactionalAuditPublisher.builder()
            .outbox(
                new R2dbcOutboxWriter(
                    new R2dbcOutboxStore(sql, "axiz_outbox", T), Clock.systemUTC()))
            .destination("audit")
            .fallback(event -> Mono.fromRunnable(() -> fallback.add(event)))
            .outside(AuditOutsideTransaction.FAIL)
            .transactions(new R2dbcTransactionManager(connections))
            .failureTimeout(T)
            .failureConcurrency(4)
            .state(auditState())
            .chain("durable")
            .calculator(new AuditSealCalculator(AuditSealer.Mode.HASH, null, null))
            .degradation(degradation())
            .build();
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
    assertEquals(1, auditEvents().stream().filter(e -> e.id().equals("audit-proof-1")).count());
    AuditEvent persisted =
        auditEvents().stream()
            .filter(e -> e.id().equals("audit-proof-1"))
            .findFirst()
            .orElseThrow();
    org.junit.jupiter.api.Assertions.assertFalse(
        AuditEventJson.write(persisted).contains("hidden"));
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
    assertEquals(
        1,
        auditEvents().stream()
            .filter(e -> e.id().equals("audit-proof-2") && e.outcome() == Outcome.FAILURE)
            .count());
    assertEquals(0, fallback.size());

    var rejected =
        ReactiveTransactionalAuditPublisher.builder()
            .outbox(
                new R2dbcOutboxWriter(
                    new R2dbcOutboxStore(sql, "missing_audit_table", T), Clock.systemUTC()))
            .destination("audit")
            .fallback(event -> Mono.empty())
            .outside(AuditOutsideTransaction.FAIL)
            .transactions(new R2dbcTransactionManager(connections))
            .failureTimeout(T)
            .failureConcurrency(4)
            .state(auditState())
            .chain("durable")
            .calculator(new AuditSealCalculator(AuditSealer.Mode.HASH, null, null))
            .degradation(degradation())
            .build();
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
        new R2dbcOutboxWriter(new R2dbcOutboxStore(sql, "axiz_outbox", T), Clock.systemUTC());
    new ReactiveWebApplicationContextRunner()
        .withPropertyValues("axiz.config.environment=test")
        .withConfiguration(
            AutoConfigurations.of(
                AxizAuditAutoConfiguration.class,
                AxizNativeAuditAutoConfiguration.class,
                AxizHexagonalAutoConfiguration.class,
                AxizReactiveHexagonalAutoConfiguration.class))
        .withBean(ReactiveOutboxWriter.class, () -> writer)
        .withBean(ReactiveAuditChainState.class, PostgresReactiveUseCaseIT::auditState)
        .withBean(ReactiveTransactionManager.class, () -> new R2dbcTransactionManager(connections))
        .withPropertyValues(
            "axiz.audit.mode=DURABLE",
            "axiz.outbox.relay.enabled=false",
            "axiz.audit.durable.outside-transaction=fail")
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
              assertEquals(
                  1,
                  auditEvents().stream().filter(e -> e.action().equals("account.change")).count());
            });
  }

  private static R2dbcAuditChainState auditState() {
    return new R2dbcAuditChainState(sql, connections, "axiz_audit_chain", Clock.systemUTC(), T);
  }

  private static AuditSealer degradation() {
    return new AuditSealer(
        "degradation",
        AuditSealer.Mode.HASH,
        new InMemoryAuditChainState(Clock.systemUTC(), 8),
        null,
        null);
  }

  private static List<AuditEvent> auditEvents() {
    return sql.query(
            "SELECT envelope FROM axiz_outbox WHERE destination='audit'",
            SqlParams.of(),
            row ->
                AuditEventJson.read(
                    new String(
                        CloudEventJson.read(row.require("envelope", String.class)).data(),
                        StandardCharsets.UTF_8)))
        .collectList()
        .block(T);
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
