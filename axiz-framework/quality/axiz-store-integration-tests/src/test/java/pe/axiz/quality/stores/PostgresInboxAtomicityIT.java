package pe.axiz.quality.stores;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.postgresql.ds.PGSimpleDataSource;
import org.reactivestreams.Publisher;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import pe.axiz.adapter.inbox.jdbc.JdbcInboxStore;
import pe.axiz.core.messaging.CompletesInTransaction;
import pe.axiz.core.messaging.DeliveryDecision;
import pe.axiz.core.messaging.EventDispatcher;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.HandlesEvent;
import pe.axiz.kit.r2dbc.ReactiveSql;
import pe.axiz.quality.containers.TestContainerImages;
import pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap;
import pe.axiz.spring.inbox.AxizInboxAutoConfiguration;
import pe.axiz.spring.inbox.r2dbc.AxizInboxR2dbcAutoConfiguration;
import pe.axiz.spring.messaging.AxizMessagingAutoConfiguration;
import pe.axiz.spring.messaging.ReactiveTypedEventHandler;
import pe.axiz.spring.r2dbc.DatabaseClientReactiveSql;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * Los efectos de negocio y la finalización del inbox deben compartir el recurso R2DBC real de
 * Spring.
 */
@ExtendWith(BlockHoundExtension.class)
@Timeout(60)
class PostgresInboxAtomicityIT {
  private static final PostgreSQLContainer POSTGRES = TestContainerImages.postgres();
  private static PGSimpleDataSource source;
  private static ConnectionFactory factory;
  private Handler handler;

  @BeforeAll
  static void start() {
    Docker.require();
    POSTGRES.start();
    source = new PGSimpleDataSource();
    source.setURL(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    factory =
        ConnectionFactories.get(
            ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(ConnectionFactoryOptions.HOST, POSTGRES.getHost())
                .option(ConnectionFactoryOptions.PORT, POSTGRES.getMappedPort(5432))
                .option(ConnectionFactoryOptions.DATABASE, POSTGRES.getDatabaseName())
                .option(ConnectionFactoryOptions.USER, POSTGRES.getUsername())
                .option(ConnectionFactoryOptions.PASSWORD, POSTGRES.getPassword())
                .build());
    ReactiveInfrastructureBootstrap.initialize();
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @BeforeEach
  void schema() throws Exception {
    try (var connection = source.getConnection();
        var statement = connection.createStatement();
        var input = JdbcInboxStore.class.getResourceAsStream("/axiz/sql/inbox-postgresql.sql")) {
      statement.execute("DROP TABLE IF EXISTS axiz_inbox");
      statement.execute("DROP TABLE IF EXISTS inbox_effects");
      assertNotNull(input);
      for (String sql :
          new String(input.readAllBytes(), StandardCharsets.UTF_8)
              .replaceAll("(?m)--.*", "")
              .split(";")) if (!sql.isBlank()) statement.execute(sql);
      // Sin clave de negocio única: la supresión de duplicados la prueba el framework, no el
      // fixture.
      statement.execute("CREATE TABLE inbox_effects(id VARCHAR(100))");
    }
    handler = new Handler(DatabaseClient.create(factory));
  }

  @HandlesEvent("atomic.created")
  static class Handler extends ReactiveTypedEventHandler<String> {
    private final DatabaseClient client;
    final CountDownLatch written = new CountDownLatch(1);
    final Sinks.One<Void> resume = Sinks.one();
    volatile boolean pause;

    Handler(DatabaseClient client) {
      this.client = client;
    }

    @Override
    protected Mono<Void> on(String payload, EventEnvelope event) {
      return client
          .sql("INSERT INTO inbox_effects VALUES (:id)")
          .bind("id", event.id())
          .fetch()
          .rowsUpdated()
          .then(
              Mono.defer(
                  () -> {
                    written.countDown();
                    return pause ? resume.asMono() : Mono.empty();
                  }))
          .subscribeOn(Schedulers.parallel());
    }
  }

  private ReactiveWebApplicationContextRunner runner(ConnectionFactory inboxFactory, String mode) {
    return new ReactiveWebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizInboxAutoConfiguration.class,
                AxizInboxR2dbcAutoConfiguration.class,
                AxizMessagingAutoConfiguration.class))
        .withPropertyValues(
            "axiz.inbox.store=r2dbc",
            "axiz.inbox.transactional=" + mode,
            "axiz.messaging.consumer.name=atomic")
        .withBean(
            ReactiveSql.class,
            () -> new DatabaseClientReactiveSql(DatabaseClient.create(inboxFactory)))
        .withBean(ReactiveTransactionManager.class, () -> new R2dbcTransactionManager(factory))
        .withBean("handler", Handler.class, () -> handler);
  }

  private EventEnvelope event() {
    return EventEnvelope.builder()
        .id("one")
        .source("urn:source")
        .type("atomic.created")
        .time(Instant.now())
        .subject(null)
        .dataContentType("application/json")
        .data("\"value\"".getBytes(StandardCharsets.UTF_8))
        .extensions(Map.of())
        .build();
  }

  private int count(String table) throws Exception {
    try (var connection = source.getConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
      assertTrue(rows.next());
      return rows.getInt(1);
    }
  }

  @Test
  void completionFailureRollsBackTheBusinessWrite() {
    runner(factory, "true")
        .run(
            ctx -> {
              assertNull(ctx.getStartupFailure());
              try (var connection = source.getConnection();
                  var statement = connection.createStatement()) {
                statement.execute(
                    "ALTER TABLE axiz_inbox ADD CONSTRAINT reject_processed CHECK(status <>"
                        + " 'PROCESSED')");
              }
              assertEquals(
                  DeliveryDecision.RETRY, ctx.getBean(EventDispatcher.class).dispatch(event(), 1));
              assertEquals(0, count("inbox_effects"));
            });
  }

  @Test
  void committedCompletionSurvivesRedeliveryAndDoesNotRunTheBusinessTwice() {
    runner(factory, "true")
        .run(
            ctx -> {
              var dispatcher = ctx.getBean(EventDispatcher.class);
              var event = event();
              assertEquals(DeliveryDecision.ACK, dispatcher.dispatch(event, 1));
              assertEquals(DeliveryDecision.ACK, dispatcher.dispatch(event, 2));
              assertEquals(1, count("inbox_effects"));
              assertEquals(1, count("axiz_inbox"));
            });
  }

  @Test
  void losingTheLeaseBeforeCompletionRollsBackTheBusinessEffect() {
    handler.pause = true;
    runner(factory, "true")
        .run(
            ctx -> {
              var dispatcher = ctx.getBean(EventDispatcher.class);
              try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                var delivery = workers.submit(() -> dispatcher.dispatch(event(), 1));
                assertTrue(handler.written.await(5, TimeUnit.SECONDS));
                try (var connection = source.getConnection();
                    var statement = connection.createStatement()) {
                  assertEquals(
                      1, statement.executeUpdate("UPDATE axiz_inbox SET owner='new-owner'"));
                }
                assertEquals(Sinks.EmitResult.OK, handler.resume.tryEmitEmpty());
                assertEquals(DeliveryDecision.RETRY, delivery.get(10, TimeUnit.SECONDS));
              }
              assertEquals(0, count("inbox_effects"));
            });
  }

  @Test
  void twoConcurrentDeliveriesCommitOnlyOneEffect() {
    runner(factory, "true")
        .run(
            ctx -> {
              var dispatcher = ctx.getBean(EventDispatcher.class);
              var gate = new CountDownLatch(1);
              var event = event();
              try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                var first =
                    workers.submit(
                        () -> {
                          gate.await();
                          return dispatcher.dispatch(event, 1);
                        });
                var second =
                    workers.submit(
                        () -> {
                          gate.await();
                          return dispatcher.dispatch(event, 1);
                        });
                gate.countDown();
                // La entrega que pierde la carrera choca con el registro del inbox (RETRY) o
                // encuentra
                // el lease vigente de la otra (DEFER); si llega cuando la otra ya terminó, es un
                // duplicado (ACK).
                var outcomes =
                    EnumSet.of(
                        DeliveryDecision.ACK, DeliveryDecision.RETRY, DeliveryDecision.DEFER);
                assertTrue(outcomes.contains(first.get(10, TimeUnit.SECONDS)));
                assertTrue(outcomes.contains(second.get(10, TimeUnit.SECONDS)));
              }
              assertEquals(1, count("inbox_effects"));
              assertEquals(DeliveryDecision.ACK, dispatcher.dispatch(event, 2));
              assertEquals(1, count("inbox_effects"));
            });
  }

  @Test
  void distinctTransactionResourcesFailExplicitModeAndDisableAutomaticCompletion() {
    ConnectionFactory other =
        new ConnectionFactory() {
          @Override
          public Publisher<? extends Connection> create() {
            return factory.create();
          }

          @Override
          public ConnectionFactoryMetadata getMetadata() {
            return factory.getMetadata();
          }
        };
    runner(other, "true").run(ctx -> assertNotNull(ctx.getStartupFailure()));
    runner(other, "auto")
        .run(
            ctx -> {
              assertNull(ctx.getStartupFailure());
              assertFalse(
                  ((CompletesInTransaction<?>) ctx.getBean("handler")).completesInTransaction());
              assertEquals(
                  DeliveryDecision.ACK, ctx.getBean(EventDispatcher.class).dispatch(event(), 1));
              assertEquals(1, count("inbox_effects"));
            });
  }
}
