package pe.axiz.quality.stores;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import pe.axiz.adapter.inbox.jdbc.JdbcInboxStore;
import pe.axiz.adapter.inbox.r2dbc.R2dbcInboxStore;
import pe.axiz.kit.jdbc.JdbcTransactions;
import pe.axiz.kit.r2dbc.R2dbcSql;
import pe.axiz.test.contracts.InboxStoreContract;

/**
 * Inbox contract on PostgreSQL (Aurora) through JDBC and R2DBC: row locks, conditional updates,
 * timestamps and the shipped DDL.
 */
@org.junit.jupiter.api.extension.ExtendWith(pe.axiz.spring.test.BlockHoundExtension.class)
class PostgresInboxStoresIT {

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse(Docker.POSTGRES))
          .withDatabaseName("app")
          .withUsername("app")
          .withPassword("local-only");

  private static PGSimpleDataSource dataSource;
  private static ConnectionFactory connections;

  @BeforeAll
  static void start() throws Exception {
    Docker.require();
    pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap.initialize();
    POSTGRES.start();
    dataSource = new PGSimpleDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    try (Connection c = dataSource.getConnection();
        Statement s = c.createStatement();
        InputStream in =
            JdbcInboxStore.class.getResourceAsStream("/axiz/sql/inbox-postgresql.sql")) {
      for (String sql : new String(in.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
        if (!sql.replaceAll("--.*", "").isBlank()) {
          s.execute(sql);
        }
      }
    }
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
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
    org.junit.jupiter.api.Assertions.assertTrue(
        pe.axiz.spring.test.BlockHoundExtension.detectedBlockingCalls().isEmpty(),
        () -> pe.axiz.spring.test.BlockHoundExtension.detectedBlockingCalls().toString());
  }

  @TestFactory
  Stream<DynamicTest> jdbcContract() {
    return InboxStoreContract.checks(
            () -> new JdbcInboxStore(new JdbcTransactions(dataSource), "axiz_inbox"))
        .stream()
        .map(c -> DynamicTest.dynamicTest(c.name(), c::run));
  }

  @TestFactory
  Stream<DynamicTest> r2dbcContract() {
    return InboxStoreContract.checks(
            () ->
                new R2dbcInboxStore(
                    new R2dbcSql(connections), "axiz_inbox", Duration.ofSeconds(10)))
        .stream()
        .map(c -> DynamicTest.dynamicTest(c.name(), c::run));
  }

  @TestFactory
  Stream<DynamicTest> nativeR2dbcContract() {
    return pe.axiz.test.contracts.AsyncInboxStoreContract.checks(
            () ->
                NativeInboxContractAdapters.r2dbc(
                    new pe.axiz.adapter.inbox.r2dbc.R2dbcReactiveInboxStore(
                        new R2dbcSql(connections), "axiz_inbox")),
            command -> reactor.core.scheduler.Schedulers.parallel().schedule(command))
        .stream()
        .map(check -> DynamicTest.dynamicTest("native: " + check.name(), check::run));
  }

  @org.junit.jupiter.api.Test
  void nativeInboxKeepsTheCallingTransactionContextAcrossThreadChanges() {
    var sql = new R2dbcSql(connections);
    var store = new pe.axiz.adapter.inbox.r2dbc.R2dbcReactiveInboxStore(sql, "axiz_inbox");
    var now = java.time.Instant.now();
    String id = java.util.UUID.randomUUID().toString();
    var rollback =
        sql.inTransaction(
            tx ->
                store
                    .tryAcquire("tx", id, "a", now, now.plusSeconds(30), now.plusSeconds(3600))
                    .publishOn(reactor.core.scheduler.Schedulers.parallel())
                    .then(
                        reactor.core.publisher.Mono.error(new IllegalStateException("rollback"))));
    reactor.test.StepVerifier.create(
            rollback.subscribeOn(reactor.core.scheduler.Schedulers.parallel()))
        .expectErrorMessage("rollback")
        .verify(Duration.ofSeconds(30));
    reactor.test.StepVerifier.create(
            store
                .tryAcquire("tx", id, "b", now, now.plusSeconds(30), now.plusSeconds(3600))
                .subscribeOn(reactor.core.scheduler.Schedulers.parallel()))
        .assertNext(
            acquisition ->
                org.junit.jupiter.api.Assertions.assertEquals(
                    pe.axiz.core.inbox.InboxStore.Acquisition.Status.ACQUIRED,
                    acquisition.status()))
        .expectComplete()
        .verify(Duration.ofSeconds(30));
  }
}
