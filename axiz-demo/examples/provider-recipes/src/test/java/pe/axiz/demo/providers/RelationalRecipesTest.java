package pe.axiz.demo.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.PayloadCodec;
import pe.axiz.kit.r2dbc.ReactiveSql;
import pe.axiz.spring.inbox.jdbc.AxizInboxJdbcAutoConfiguration;
import pe.axiz.spring.outbox.AxizOutboxAutoConfiguration;
import pe.axiz.spring.outbox.ReactiveTransactionalEvents;
import pe.axiz.spring.outbox.r2dbc.AxizOutboxR2dbcAutoConfiguration;
import pe.axiz.spring.r2dbc.DatabaseClientReactiveSql;
import pe.axiz.spring.scheduling.jdbc.AxizSchedulingJdbcAutoConfiguration;
import pe.axiz.spring.scheduling.r2dbc.AxizSchedulingR2dbcAutoConfiguration;
import pe.axiz.spring.sequence.AxizSequenceAutoConfiguration;
import pe.axiz.spring.sequence.ReactiveSequences;
import pe.axiz.spring.sequence.Sequences;
import pe.axiz.spring.sequence.jdbc.AxizSequenceJdbcAutoConfiguration;
import pe.axiz.spring.sequence.r2dbc.AxizSequenceR2dbcAutoConfiguration;
import pe.axiz.spring.test.AxizContainers;
import reactor.core.publisher.Mono;

/**
 * Las recetas se encargan del aprovisionamiento; las operaciones usan puertos públicos obtenidos de
 * los starters seleccionados.
 */
class RelationalRecipesTest {
  private static final Duration TIMEOUT = Duration.ofSeconds(15);
  private static final PostgreSQLContainer POSTGRES = AxizContainers.postgres();
  private static final PGSimpleDataSource DATA = new PGSimpleDataSource();
  private static ConnectionFactory connections;

  @BeforeAll
  static void infrastructure() throws Exception {
    POSTGRES.start();
    DATA.setUrl(POSTGRES.getJdbcUrl());
    DATA.setUser(POSTGRES.getUsername());
    DATA.setPassword(POSTGRES.getPassword());
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
    for (String resource : new String[] {"sequence", "shedlock", "outbox"}) {
      new ResourceDatabasePopulator(
              new ClassPathResource("axiz/sql/" + resource + "-postgresql.sql"))
          .execute(DATA);
    }
    new JdbcTemplate(DATA).execute("CREATE TABLE recipe_changes (id VARCHAR(32) PRIMARY KEY)");
  }

  @AfterAll
  static void cleanup() {
    POSTGRES.stop();
  }

  @Test
  void jdbcSequenceReservationSurvivesBusinessRollback(TestReporter evidence) {
    var manager = new DataSourceTransactionManager(DATA);
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizSequenceAutoConfiguration.class, AxizSequenceJdbcAutoConfiguration.class))
        .withBean(DataSource.class, () -> DATA)
        .withBean(PlatformTransactionManager.class, () -> manager)
        .withPropertyValues(
            "axiz.sequence.initialize-schema=always",
            "axiz.sequence.table=recipe_sequence",
            "axiz.sequence.sequences.payment.prefix=PAY")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var numbers = context.getBean(Sequences.class);
              assertThrows(
                  IllegalStateException.class,
                  () ->
                      new TransactionTemplate(manager)
                          .execute(
                              status -> {
                                assertThat(numbers.next("payment")).isEqualTo("PAY00000001");
                                throw new IllegalStateException("business rollback");
                              }));
              assertThat(numbers.next("payment")).isEqualTo("PAY00000002");
              assertThat(
                      new JdbcTemplate(DATA)
                          .queryForObject("SELECT COUNT(*) FROM recipe_sequence", Long.class))
                  .isEqualTo(1);
            });

    evidence.publishEntry("axiz.capability.sequence", "aurora,servlet");
  }

  @Test
  void r2dbcSequenceHasNoBlockingPortAndDoesNotReuseRolledBackNumbers(TestReporter evidence) {
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizSequenceAutoConfiguration.class, AxizSequenceR2dbcAutoConfiguration.class))
        .withBean(ConnectionFactory.class, () -> connections)
        .withPropertyValues(
            "axiz.sequence.store=r2dbc", "axiz.sequence.sequences.refund.prefix=REF")
        .run(
            context -> {
              assertThat(context).hasNotFailed().doesNotHaveBean(Sequences.class);
              var numbers = context.getBean(ReactiveSequences.class);
              var tx = TransactionalOperator.create(new R2dbcTransactionManager(connections));
              assertThrows(
                  IllegalStateException.class,
                  () ->
                      tx.transactional(
                              numbers
                                  .next("refund")
                                  .doOnNext(value -> assertThat(value).isEqualTo("REF00000001"))
                                  .then(Mono.error(new IllegalStateException("business rollback"))))
                          .block(TIMEOUT));
              assertThat(numbers.next("refund").block(TIMEOUT)).isEqualTo("REF00000002");
            });

    evidence.publishEntry("axiz.capability.sequence", "r2dbc,reactive");
  }

  @Test
  void jdbcInboxRejectsAnotherOwnerAndRemembersCommittedDelivery(TestReporter evidence) {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizInboxJdbcAutoConfiguration.class))
        .withBean(DataSource.class, () -> DATA)
        .withPropertyValues(
            "axiz.inbox.table=recipe_inbox",
            "axiz.inbox.initialize-schema=always",
            "axiz.inbox.purge-interval=1h")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var inbox = context.getBean(InboxStore.class);
              var now = Instant.now();
              assertThat(
                      inbox
                          .tryAcquire(
                              "receipts",
                              "event-1",
                              "worker-a",
                              now,
                              now.plusSeconds(60),
                              now.plusSeconds(3600))
                          .status())
                  .isEqualTo(InboxStore.Acquisition.Status.ACQUIRED);
              assertThat(
                      inbox
                          .tryAcquire(
                              "receipts",
                              "event-1",
                              "worker-b",
                              now,
                              now.plusSeconds(60),
                              now.plusSeconds(3600))
                          .status())
                  .isEqualTo(InboxStore.Acquisition.Status.BUSY);
              assertThat(inbox.markProcessed("receipts", "event-1", "worker-b", now)).isFalse();
              assertThat(inbox.markProcessed("receipts", "event-1", "worker-a", now)).isTrue();
              assertThat(
                      inbox
                          .tryAcquire(
                              "receipts",
                              "event-1",
                              "worker-b",
                              now,
                              now.plusSeconds(60),
                              now.plusSeconds(3600))
                          .status())
                  .isEqualTo(InboxStore.Acquisition.Status.PROCESSED);
            });

    evidence.publishEntry("axiz.capability.inbox", "aurora");
  }

  @Test
  void schedulingProvidersExcludeCompetingWorkers(TestReporter evidence) {
    RecipeStacks.runner("servlet")
        .withConfiguration(AutoConfigurations.of(AxizSchedulingJdbcAutoConfiguration.class))
        .withBean(DataSource.class, () -> DATA)
        .withPropertyValues(
            "axiz.scheduling.initialize-schema=always", "axiz.scheduling.table=recipe_locks")
        .run(
            context -> {
              lockContract(context.getBean(LockProvider.class), "jdbc-job");
              evidence.publishEntry("axiz.capability.scheduling", "aurora,servlet");
            });
    RecipeStacks.runner("reactive")
        .withConfiguration(AutoConfigurations.of(AxizSchedulingR2dbcAutoConfiguration.class))
        .withBean(ConnectionFactory.class, () -> connections)
        .withPropertyValues("axiz.scheduling.store=r2dbc")
        .run(
            context -> {
              lockContract(context.getBean(LockProvider.class), "r2dbc-job");
              evidence.publishEntry("axiz.capability.scheduling", "r2dbc,reactive");
            });
  }

  static void lockContract(LockProvider locks, String name) {
    var specification =
        new LockConfiguration(Instant.now(), name, Duration.ofMinutes(1), Duration.ZERO);
    var first = locks.lock(specification).orElseThrow();
    try {
      assertThat(locks.lock(specification)).isEmpty();
    } finally {
      first.unlock();
    }
    locks.lock(specification).orElseThrow().unlock();
  }

  @Test
  void r2dbcOutboxCommitsWithBusinessDataAndRollsBackTogether(TestReporter evidence) {
    var client = DatabaseClient.create(connections);
    var txManager = new R2dbcTransactionManager(connections);
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizOutboxAutoConfiguration.class, AxizOutboxR2dbcAutoConfiguration.class))
        .withBean(ConnectionFactory.class, () -> connections)
        .withBean(ReactiveSql.class, () -> new DatabaseClientReactiveSql(client))
        .withBean(ReactiveTransactionManager.class, () -> txManager)
        .withBean(
            EventFactory.class,
            () ->
                new EventFactory(
                    "urn:demo:recipes", "demo", TEXT, IdGenerator.uuidV7(), Clock.systemUTC()))
        .withPropertyValues("axiz.outbox.store=r2dbc", "axiz.outbox.relay.enabled=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var events = context.getBean(ReactiveTransactionalEvents.class);
              var tx = TransactionalOperator.create(txManager);
              assertThrows(
                  IllegalStateException.class,
                  () ->
                      events
                          .enqueue("receipts", "outside", "created", "receipt", "x", "example")
                          .block(TIMEOUT));
              for (boolean rollback : new boolean[] {true, false}) {
                String id = rollback ? "rolled-back" : "committed";
                Mono<Void> work =
                    client
                        .sql("INSERT INTO recipe_changes (id) VALUES (:id)")
                        .bind("id", id)
                        .fetch()
                        .rowsUpdated()
                        .then(
                            events.enqueue(
                                "receipts", id, "created", "receipt", id, "non-sensitive example"))
                        .then(
                            rollback
                                ? Mono.error(new IllegalStateException("rollback"))
                                : Mono.empty());
                if (rollback)
                  assertThrows(
                      IllegalStateException.class, () -> tx.transactional(work).block(TIMEOUT));
                else tx.transactional(work).block(TIMEOUT);
                var jdbc = new JdbcTemplate(DATA);
                assertThat(
                        jdbc.queryForObject(
                            "SELECT COUNT(*) FROM recipe_changes WHERE id=?", Long.class, id))
                    .isEqualTo(rollback ? 0 : 1);
                assertThat(
                        jdbc.queryForObject(
                            "SELECT COUNT(*) FROM axiz_outbox WHERE id=?", Long.class, id))
                    .isEqualTo(rollback ? 0 : 1);
              }
            });

    evidence.publishEntry("axiz.capability.outbox", "r2dbc,reactive");
  }

  private static final PayloadCodec TEXT =
      new PayloadCodec() {
        public String contentType() {
          return "text/plain";
        }

        public byte[] encode(Object value) {
          return value.toString().getBytes(StandardCharsets.UTF_8);
        }

        public <T> T decode(byte[] value, Class<T> type) {
          return type.cast(new String(value, StandardCharsets.UTF_8));
        }
      };
}
