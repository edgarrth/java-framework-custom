package pe.axiz.quality.stores;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import pe.axiz.kit.jdbc.JdbcTransactions;
import pe.axiz.kit.jdbc.TransactionOutcomeUnknownException;
import pe.axiz.quality.containers.TestContainerImages;

/**
 * Injects driver failures around a real PostgreSQL transaction and a one-connection Hikari pool.
 */
@Timeout(60)
class PostgresJdbcTransactionDisposalIT {
  private static final PostgreSQLContainer POSTGRES = TestContainerImages.postgres();
  private static PGSimpleDataSource database;

  @BeforeAll
  static void start() {
    Docker.require();
    POSTGRES.start();
    database = new PGSimpleDataSource();
    database.setURL(POSTGRES.getJdbcUrl());
    database.setUser(POSTGRES.getUsername());
    database.setPassword(POSTGRES.getPassword());
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @BeforeEach
  void schema() throws SQLException {
    try (var connection = database.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE IF NOT EXISTS transaction_effects(id INTEGER)");
      statement.execute("TRUNCATE transaction_effects");
    }
  }

  @Test
  void failedRollbackCannotImplicitlyCommitAndThePhysicalConnectionIsDiscarded() throws Exception {
    try (var pool = pool()) {
      var fault = new Fault(pool, Failure.ROLLBACK);
      var transactions = new JdbcTransactions(fault.source());
      var businessFailure = new IllegalArgumentException("business failure");
      assertSame(
          businessFailure,
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  transactions.inTransaction(
                      () -> {
                        write(transactions.get());
                        throw businessFailure;
                      })));
      assertEquals(0, fault.restores.get(), "failed rollback must never restore auto-commit");
      assertEquals(1, fault.aborts.get());
      assertEquals(0, count(), "no implicit commit through cleanup or pool return");
      assertFreshConnection(pool, fault.backend);
      transactions.inTransaction(
          () -> {
            write(transactions.get());
            return null;
          });
      assertEquals(1, count(), "thread binding was removed and a new transaction works");
    }
  }

  @Test
  void lostCommitAcknowledgementIsUnknownWithoutRetryOrRollback() throws Exception {
    try (var pool = pool()) {
      var fault = new Fault(pool, Failure.COMMIT_ACKNOWLEDGEMENT);
      var transactions = new JdbcTransactions(fault.source());
      assertThrows(
          TransactionOutcomeUnknownException.class,
          () ->
              transactions.inTransaction(
                  () -> {
                    write(transactions.get());
                    return "unconfirmed";
                  }));
      assertEquals(1, fault.commits.get(), "no retry of an ambiguous commit");
      assertEquals(0, fault.rollbacks.get());
      assertEquals(0, fault.restores.get());
      assertEquals(1, fault.aborts.get());
      assertEquals(1, count(), "the server committed before its acknowledgement was lost");
      assertFreshConnection(pool, fault.backend);
    }
  }

  @Test
  void cleanupFailurePreservesConfirmedResultAndDiscardsThePhysicalConnection() throws Exception {
    for (Failure failure : new Failure[] {Failure.RESTORE, Failure.CLOSE}) {
      try (var pool = pool()) {
        var fault = new Fault(pool, failure);
        var transactions = new JdbcTransactions(fault.source());
        assertEquals(
            "confirmed",
            transactions.inTransaction(
                () -> {
                  write(transactions.get());
                  return "confirmed";
                }));
        assertEquals(1, fault.commits.get());
        assertEquals(1, fault.aborts.get());
        assertFreshConnection(pool, fault.backend);
      }
    }
    assertEquals(2, count());
  }

  private static HikariDataSource pool() {
    var pool = new HikariDataSource();
    pool.setDataSource(database);
    pool.setMaximumPoolSize(1);
    pool.setMinimumIdle(0);
    pool.setConnectionTimeout(5000);
    return pool;
  }

  private static void write(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO transaction_effects VALUES(1)");
    }
  }

  private static int count() throws SQLException {
    try (var connection = database.getConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT count(*) FROM transaction_effects")) {
      rows.next();
      return rows.getInt(1);
    }
  }

  private static int backend(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
      rows.next();
      return rows.getInt(1);
    }
  }

  private static void assertFreshConnection(HikariDataSource pool, int poisoned)
      throws SQLException {
    try (var next = pool.getConnection()) {
      assertNotEquals(poisoned, backend(next));
    }
  }

  private enum Failure {
    ROLLBACK,
    COMMIT_ACKNOWLEDGEMENT,
    RESTORE,
    CLOSE
  }

  private static final class Fault {
    private final HikariDataSource pool;
    private final Failure failure;
    private final AtomicBoolean first = new AtomicBoolean(true);
    private final AtomicBoolean injected = new AtomicBoolean();
    final AtomicInteger restores = new AtomicInteger();
    final AtomicInteger aborts = new AtomicInteger();
    final AtomicInteger commits = new AtomicInteger();
    final AtomicInteger rollbacks = new AtomicInteger();
    int backend;

    Fault(HikariDataSource pool, Failure failure) {
      this.pool = pool;
      this.failure = failure;
    }

    DataSource source() {
      return (DataSource)
          Proxy.newProxyInstance(
              DataSource.class.getClassLoader(),
              new Class<?>[] {DataSource.class},
              (proxy, method, arguments) -> {
                if (method.getName().equals("getConnection") && first.compareAndSet(true, false)) {
                  Connection connection = pool.getConnection();
                  backend = backend(connection);
                  return wrap(connection);
                }
                return invoke(pool, method, arguments);
              });
    }

    private Connection wrap(Connection delegate) {
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, arguments) -> {
                String name = method.getName();
                if (name.equals("abort")) aborts.incrementAndGet();
                if (name.equals("commit")) {
                  commits.incrementAndGet();
                  if (failure == Failure.COMMIT_ACKNOWLEDGEMENT
                      && injected.compareAndSet(false, true)) {
                    delegate.commit();
                    throw new SQLException("acknowledgement lost", "08006");
                  }
                }
                if (name.equals("rollback")) {
                  rollbacks.incrementAndGet();
                  if (failure == Failure.ROLLBACK && injected.compareAndSet(false, true))
                    throw new SQLException("rollback failed", "08006");
                }
                if (name.equals("setAutoCommit") && Boolean.TRUE.equals(arguments[0])) {
                  restores.incrementAndGet();
                  if (failure == Failure.RESTORE && injected.compareAndSet(false, true))
                    throw new SQLException("restore failed", "08006");
                }
                if (name.equals("close")
                    && failure == Failure.CLOSE
                    && injected.compareAndSet(false, true))
                  throw new SQLException("close failed", "08006");
                return invoke(delegate, method, arguments);
              });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] arguments)
        throws Throwable {
      try {
        return method.invoke(target, arguments);
      } catch (InvocationTargetException failure) {
        throw failure.getCause();
      }
    }
  }
}
