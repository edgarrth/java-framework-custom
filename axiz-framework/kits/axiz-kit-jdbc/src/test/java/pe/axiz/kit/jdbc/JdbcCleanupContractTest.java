package pe.axiz.kit.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class JdbcCleanupContractTest {
  private static final class Driver {
    final List<String> calls = new ArrayList<>();
    int acquisitions;
    boolean autoCommit = true;
    final Connection connection;
    final JdbcTransactions transactions;

    Driver(Set<String> failures) {
      connection =
          (Connection)
              Proxy.newProxyInstance(
                  Connection.class.getClassLoader(),
                  new Class<?>[] {Connection.class},
                  (proxy, method, arguments) -> {
                    String operation = method.getName();
                    if (operation.equals("setAutoCommit"))
                      operation = Boolean.TRUE.equals(arguments[0]) ? "restore" : "begin";
                    calls.add(operation);
                    if (failures.contains(operation)) throw new SQLException(operation);
                    if (operation.equals("getAutoCommit")) return autoCommit;
                    if (operation.equals("begin")) autoCommit = false;
                    if (operation.equals("restore")) autoCommit = true;
                    return null;
                  });
      var source =
          (DataSource)
              Proxy.newProxyInstance(
                  DataSource.class.getClassLoader(),
                  new Class<?>[] {DataSource.class},
                  (proxy, method, arguments) -> {
                    if (!method.getName().equals("getConnection"))
                      throw new UnsupportedOperationException();
                    acquisitions++;
                    return connection;
                  });
      transactions = new JdbcTransactions(source);
    }
  }

  @Test
  void nestedTransactionsJoinTheirConnectionAndTheNextRequestCannotInheritIt() throws Exception {
    var driver = new Driver(Set.of());
    assertFalse(driver.transactions.isTransactionActive(driver.connection));
    assertEquals(
        "result",
        driver.transactions.inTransaction(
            () -> {
              assertTrue(driver.transactions.isTransactionActive(driver.transactions.get()));
              driver.transactions.release(driver.connection);
              assertFalse(driver.calls.contains("close"));
              assertEquals(1, driver.acquisitions);
              return driver.transactions.inTransaction(() -> "result");
            }));
    assertTrue(driver.autoCommit);
    assertEquals(1, driver.calls.stream().filter("close"::equals).count());
    driver.transactions.get();
    assertEquals(2, driver.acquisitions);
    driver.transactions.release(driver.connection);
    assertEquals(2, driver.calls.stream().filter("close"::equals).count());
  }

  @Test
  void everyCleanupFailurePreservesTheBusinessErrorAndDiscardsPoisonedConnections()
      throws Exception {
    for (Set<String> failures :
        List.of(
            Set.of("rollback"),
            Set.of("restore"),
            Set.of("close"),
            Set.of("rollback", "abort", "close"),
            Set.of("restore", "abort", "close"))) {
      var driver = new Driver(failures);
      var original = new IllegalArgumentException("work");
      var actual =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  driver.transactions.inTransaction(
                      () -> {
                        throw original;
                      }));
      assertSame(original, actual);
      assertFalse(driver.calls.contains("commit"));
      assertTrue(driver.calls.contains("abort"));
      assertTrue(driver.calls.contains("close"));
      for (String failure : failures)
        assertTrue(
            Arrays.stream(actual.getSuppressed())
                .anyMatch(error -> failure.equals(error.getMessage())),
            failure);
      if (failures.contains("rollback")) assertFalse(driver.calls.contains("restore"));
      driver.transactions.get();
      assertEquals(2, driver.acquisitions);
    }
  }

  @Test
  void failedSetupAndUnknownCommitAlwaysAttemptAbortAndCloseEvenWhenBothFail() throws Exception {
    for (String operation : List.of("getAutoCommit", "begin", "commit")) {
      var driver = new Driver(Set.of(operation, "abort", "close"));
      var actual =
          assertThrows(Exception.class, () -> driver.transactions.inTransaction(() -> "done"));
      if (operation.equals("commit")) {
        assertTrue(actual instanceof TransactionOutcomeUnknownException);
        assertEquals("commit", actual.getCause().getMessage());
      } else assertEquals(operation, actual.getMessage());
      assertEquals(
          List.of("abort", "close"),
          Arrays.stream(actual.getSuppressed()).map(Throwable::getMessage).toList());
      assertFalse(driver.calls.contains("restore"));
      assertFalse(driver.calls.contains("rollback"));
      assertEquals(1, driver.calls.stream().filter("abort"::equals).count());
      assertEquals(1, driver.calls.stream().filter("close"::equals).count());
      driver.transactions.get();
      assertEquals(2, driver.acquisitions);
    }
  }

  @Test
  void confirmedSuccessRemainsConfirmedDespiteFailingRestoreAbortAndClose() throws Exception {
    for (Set<String> failures :
        List.of(Set.of("restore", "abort", "close"), Set.of("close", "abort"))) {
      var driver = new Driver(failures);
      assertEquals("committed", driver.transactions.inTransaction(() -> "committed"));
      assertTrue(driver.calls.contains("commit"));
      assertTrue(driver.calls.contains("abort"));
      assertTrue(driver.calls.contains("close"));
      assertFalse(driver.calls.contains("rollback"));
      driver.transactions.get();
      assertEquals(2, driver.acquisitions);
    }
  }
}
