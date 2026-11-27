package pe.axiz.quality.stores;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import pe.axiz.adapter.inbox.jdbc.JdbcInboxStore;
import pe.axiz.adapter.inbox.r2dbc.R2dbcInboxStore;
import pe.axiz.adapter.inbox.r2dbc.R2dbcReactiveInboxStore;
import pe.axiz.core.inbox.InboxStore.Acquisition.Status;
import pe.axiz.kit.jdbc.JdbcTransactions;
import pe.axiz.kit.r2dbc.R2dbcSql;
import pe.axiz.quality.containers.TestContainerImages;
import pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap;
import pe.axiz.spring.test.BlockHoundExtension;
import pe.axiz.test.contracts.AsyncInboxStoreContract;
import pe.axiz.test.contracts.InboxStoreContract;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * Contrato de inbox sobre PostgreSQL (Aurora) mediante JDBC y R2DBC: locks de fila, actualizaciones
 * condicionales, marcas de tiempo y el DDL incluido.
 */
@ExtendWith(BlockHoundExtension.class)
class PostgresInboxStoresIT {

  private static final PostgreSQLContainer POSTGRES =
      TestContainerImages.postgres()
          .withDatabaseName("app")
          .withUsername("app")
          .withPassword("local-only");

  private static PGSimpleDataSource dataSource;
  private static ConnectionFactory connections;

  @BeforeAll
  static void start() throws Exception {
    Docker.require();
    ReactiveInfrastructureBootstrap.initialize();
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
    Assertions.assertTrue(
        BlockHoundExtension.detectedBlockingCalls().isEmpty(),
        () -> BlockHoundExtension.detectedBlockingCalls().toString());
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
    return AsyncInboxStoreContract.checks(
            () ->
                NativeInboxContractAdapters.r2dbc(
                    new R2dbcReactiveInboxStore(new R2dbcSql(connections), "axiz_inbox")),
            command -> Schedulers.parallel().schedule(command))
        .stream()
        .map(check -> DynamicTest.dynamicTest("native: " + check.name(), check::run));
  }

  @Test
  void nativeInboxKeepsTheCallingTransactionContextAcrossThreadChanges() {
    var sql = new R2dbcSql(connections);
    var store = new R2dbcReactiveInboxStore(sql, "axiz_inbox");
    var now = Instant.now();
    String id = UUID.randomUUID().toString();
    var rollback =
        sql.inTransaction(
            tx ->
                store
                    .tryAcquire("tx", id, "a", now, now.plusSeconds(30), now.plusSeconds(3600))
                    .publishOn(Schedulers.parallel())
                    .then(Mono.error(new IllegalStateException("rollback"))));
    StepVerifier.create(rollback.subscribeOn(Schedulers.parallel()))
        .expectErrorMessage("rollback")
        .verify(Duration.ofSeconds(30));
    StepVerifier.create(
            store
                .tryAcquire("tx", id, "b", now, now.plusSeconds(30), now.plusSeconds(3600))
                .subscribeOn(Schedulers.parallel()))
        .assertNext(acquisition -> Assertions.assertEquals(Status.ACQUIRED, acquisition.status()))
        .expectComplete()
        .verify(Duration.ofSeconds(30));
  }
}
