package pe.axiz.adapter.saga.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pe.axiz.core.saga.SagaConcurrencyException;
import pe.axiz.core.saga.SagaState;
import pe.axiz.core.saga.SagaStatus;
import pe.axiz.kit.jdbc.ConnectionProvider;
import pe.axiz.kit.jdbc.JdbcTransactions;

class JdbcSagaOwnershipTest {
  private JdbcDataSource source;
  private JdbcTransactions transactions;
  private JdbcSagaStateStore store;

  @BeforeEach
  void setup() throws Exception {
    source = new JdbcDataSource();
    source.setURL(
        "jdbc:h2:mem:ownership-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    try (var connection = source.getConnection();
        var statement = connection.createStatement();
        var schema = getClass().getResourceAsStream("/axiz/sql/saga-h2.sql")) {
      for (String sql : new String(schema.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
        if (!sql.replaceAll("--.*", "").isBlank()) statement.execute(sql);
      }
    }
    transactions = new JdbcTransactions(source);
    store = new JdbcSagaStateStore(transactions, "axiz_saga");
    store.create(state(0));
  }

  @Test
  void recoveryLimitsAndLeaseBoundsFailBeforeDatabaseWork() {
    assertTrue(store.find("absent").isEmpty());
    assertThrows(IllegalArgumentException.class, () -> store.findStale(Instant.now(), 0));
    assertThrows(IllegalArgumentException.class, () -> store.findStale(Instant.now(), 10001));
    assertEquals(1, store.findStale(Instant.now().plusSeconds(60), 1).size());
    assertEquals(1, store.findStale(Instant.now().plusSeconds(60), 10000).size());
    assertThrows(IllegalArgumentException.class, () -> store.tryAcquire("saga", Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> store.tryAcquire("saga", Duration.ofSeconds(-1)));
    try (var held = store.tryAcquire("saga", Duration.ofMinutes(1)).orElseThrow()) {
      assertEquals("saga", held.resource());
      assertTrue(held.renew(Duration.ofMinutes(2)));
      assertThrows(IllegalArgumentException.class, () -> held.renew(Duration.ZERO));
    }
  }

  @Test
  void providerFailuresExposeOnlyAValidatedSqlStateWithoutRetainingSensitiveCauses() {
    for (var failure :
        List.of(
            new SQLException("PAN-4111111111111111"),
            new SQLException("PAN-4111111111111111", "DNI-12345678"),
            new SQLException("PAN-4111111111111111", "08006"))) {
      failure.addSuppressed(new IllegalStateException("secret-canary"));
      var broken =
          new JdbcSagaStateStore(
              new ConnectionProvider() {
                public Connection get() throws SQLException {
                  throw failure;
                }

                public void release(Connection connection) {
                  throw new AssertionError("No connection acquired");
                }
              },
              "axiz_saga");
      var safe = assertThrows(IllegalStateException.class, () -> broken.find("saga"));
      assertEquals(
          "Saga store SQL failed: " + ("08006".equals(failure.getSQLState()) ? "08006" : "unknown"),
          safe.getMessage());
      assertNull(safe.getCause());
      assertEquals(0, safe.getSuppressed().length);
    }
  }

  @Test
  void acquisitionIsExclusiveAndExpiredOwnersCannotRenewWriteOrReleaseSuccessors()
      throws Exception {
    var owner = store.tryAcquire("saga", Duration.ofMinutes(1)).orElseThrow();
    assertTrue(store.tryAcquire("saga", Duration.ofMinutes(1)).isEmpty());
    assertTrue(store.findStale(Instant.now().plusSeconds(3600), 10).isEmpty());
    store.update(state(1), owner.token());
    try (var connection = source.getConnection();
        var statement = connection.createStatement()) {
      statement.executeUpdate(
          "UPDATE axiz_saga SET execution_until = TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00'");
    }
    assertFalse(owner.renew(Duration.ofMinutes(1)));
    assertThrows(SagaConcurrencyException.class, () -> store.update(state(2), owner.token()));
    assertEquals(1, store.findStale(Instant.now().plusSeconds(3600), 10).size());
    var successor = store.tryAcquire("saga", Duration.ofMinutes(1)).orElseThrow();
    owner.release();
    assertTrue(store.tryAcquire("saga", Duration.ofMinutes(1)).isEmpty());
    store.update(state(2), successor.token());
    successor.release();
    assertTrue(store.tryAcquire("saga", Duration.ofMinutes(1)).isPresent());
  }

  @Test
  void concurrentReplicasHaveOnlyOneWinner() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> {
                start.await();
                return store.tryAcquire("saga", Duration.ofMinutes(1));
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return store.tryAcquire("saga", Duration.ofMinutes(1));
              });
      start.countDown();
      assertEquals(
          1,
          (first.get(5, TimeUnit.SECONDS).isPresent() ? 1 : 0)
              + (second.get(5, TimeUnit.SECONDS).isPresent() ? 1 : 0));
    }
  }

  @Test
  void stateAndLeaseCannotRemainUncommittedInTheCallersTransaction() {
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.inTransaction(
                () -> {
                  store.tryAcquire("saga", Duration.ofMinutes(1));
                  return null;
                }));
    assertTrue(store.tryAcquire("saga", Duration.ofMinutes(1)).isPresent());
  }

  private static SagaState state(long version) {
    return SagaState.builder()
        .sagaId("saga")
        .sagaName("payment")
        .status(SagaStatus.RUNNING)
        .context("{}")
        .updatedAt(Instant.now())
        .version(version)
        .build();
  }
}
