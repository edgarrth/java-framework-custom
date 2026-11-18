package pe.axiz.quality.stores;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.postgresql.PostgreSQLContainer;
import pe.axiz.adapter.sequence.r2dbc.R2dbcSequenceStore;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.sequence.ResetPolicy;
import pe.axiz.kit.r2dbc.ReactiveSql;
import pe.axiz.kit.r2dbc.SqlParams;
import pe.axiz.quality.containers.TestContainerImages;
import pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap;
import pe.axiz.spring.r2dbc.DatabaseClientReactiveSql;
import pe.axiz.spring.sequence.AxizSequenceAutoConfiguration;
import pe.axiz.spring.sequence.AxizSequenceProperties;
import pe.axiz.spring.sequence.ReactiveSequences;
import pe.axiz.spring.sequence.r2dbc.AxizSequenceR2dbcAutoConfiguration;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/** Native counter reservations commit independently of business writes, including cancellation. */
@ExtendWith(BlockHoundExtension.class)
class PostgresSequenceIT {
  private static final Duration T = Duration.ofSeconds(15);
  private static final PostgreSQLContainer POSTGRES = TestContainerImages.postgres();
  private static ConnectionFactory connections;
  private static ReactiveSql sql;
  private static R2dbcSequenceStore store;

  @BeforeAll
  static void start() {
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
    sql.update(
            "CREATE TABLE axiz_sequence (seq_name VARCHAR(64), seq_period VARCHAR(64), seq_value"
                + " BIGINT NOT NULL, PRIMARY KEY (seq_name, seq_period))",
            SqlParams.of())
        .block(T);
    sql.update("CREATE TABLE sequence_business (id VARCHAR(64) PRIMARY KEY)", SqlParams.of())
        .block(T);
    store = new R2dbcSequenceStore(connections, "axiz_sequence");
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  private static ReactiveSequences sequences(String name, int blockSize) {
    var properties =
        new AxizSequenceProperties(
            "r2dbc",
            Map.of(
                name,
                new AxizSequenceProperties.Spec(
                    "", 8, ResetPolicy.NEVER, "-", ZoneOffset.UTC, blockSize)));
    return new ReactiveSequences(
        properties,
        (key, period, amount) ->
            Stages.requireValue(store.increment(key, period, amount).toFuture()),
        Clock.systemUTC());
  }

  @Test
  void aBusinessRollbackCannotReturnACachedBlockToAnotherReplica() {
    ReactiveSequences first = sequences("rollback", 10);
    var business = TransactionalOperator.create(new R2dbcTransactionManager(connections));
    var attempt =
        first
            .next("rollback")
            .flatMap(
                value ->
                    sql.update(
                        "INSERT INTO sequence_business (id) VALUES (:id)",
                        SqlParams.of().bind("id", value)))
            .then(Mono.error(new IllegalStateException("rollback business only")));
    StepVerifier.create(business.transactional(attempt).subscribeOn(Schedulers.parallel()))
        .expectError(IllegalStateException.class)
        .verify(T);
    assertEquals(
        0L,
        sql.queryOne(
                "SELECT COUNT(*) AS total FROM sequence_business",
                SqlParams.of(),
                row -> row.require("total", Long.class))
            .block(T));
    StepVerifier.create(first.next("rollback").subscribeOn(Schedulers.parallel()))
        .expectNext("00000002")
        .verifyComplete();
    StepVerifier.create(
            sequences("rollback", 10).next("rollback").subscribeOn(Schedulers.parallel()))
        .expectNext("00000011")
        .verifyComplete();
  }

  @Test
  void nativeCounterPoolCanAdvanceWhileTheBusinessPoolIsCompletelyOccupied() {
    var main =
        new ConnectionPool(
            ConnectionPoolConfiguration.builder(connections)
                .initialSize(0)
                .maxSize(1)
                .maxAcquireTime(Duration.ofMillis(300))
                .build());
    try {
      new ReactiveWebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  AxizSequenceAutoConfiguration.class, AxizSequenceR2dbcAutoConfiguration.class))
          .withBean(ConnectionFactory.class, () -> main)
          .withPropertyValues(
              "axiz.sequence.store=r2dbc", "axiz.sequence.sequences.saturated.block-size=1")
          .run(
              context -> {
                Assertions.assertThat(context).hasNotFailed();
                var counter = context.getBean(ReactiveSequences.class);
                var transaction = TransactionalOperator.create(new R2dbcTransactionManager(main));
                var work =
                    Mono.defer(
                        () -> {
                          assertEquals(1, main.getMetrics().orElseThrow().acquiredSize());
                          return counter.next("saturated");
                        });
                StepVerifier.create(
                        transaction.transactional(work).subscribeOn(Schedulers.parallel()))
                    .expectNext("00000001")
                    .verifyComplete();
              });
    } finally {
      main.dispose();
    }
  }

  @Test
  void concurrentReplicasAllocateDisjointBlocksOnNativeConnections() {
    ReactiveSequences first = sequences("concurrent", 5);
    ReactiveSequences second = sequences("concurrent", 5);
    StepVerifier.create(
            Flux.range(0, 100)
                .flatMap(index -> (index % 2 == 0 ? first : second).next("concurrent"), 16)
                .collectList()
                .subscribeOn(Schedulers.parallel()))
        .assertNext(
            values -> {
              assertEquals(100, new HashSet<>(values).size());
              assertTrue(values.stream().allMatch(value -> Long.parseLong(value) > 0));
            })
        .expectComplete()
        .verify(T);
  }

  @Test
  void cancellingBusinessAfterAReservationKeepsThatBlockDurable() {
    var first = sequences("cancelled-business", 5);
    var reserved = Sinks.one();
    var business = TransactionalOperator.create(new R2dbcTransactionManager(connections));
    var work =
        first
            .next("cancelled-business")
            .flatMap(
                value ->
                    sql.update(
                        "INSERT INTO sequence_business (id) VALUES (:id)",
                        SqlParams.of().bind("id", value)))
            .doOnNext(ignored -> reserved.tryEmitEmpty())
            .then(Mono.never());
    var running = business.transactional(work).subscribeOn(Schedulers.parallel()).subscribe();
    reserved.asMono().block(T);
    running.dispose();
    Awaitility.await()
        .atMost(T)
        .until(
            () ->
                sql.queryOne(
                            "SELECT COUNT(*) AS total FROM sequence_business",
                            SqlParams.of(),
                            row -> row.require("total", Long.class))
                        .block(T)
                    == 0);
    StepVerifier.create(first.next("cancelled-business").subscribeOn(Schedulers.parallel()))
        .expectNext("00000002")
        .verifyComplete();
    StepVerifier.create(
            sequences("cancelled-business", 5)
                .next("cancelled-business")
                .subscribeOn(Schedulers.parallel()))
        .expectNext("00000006")
        .verifyComplete();
  }
}
