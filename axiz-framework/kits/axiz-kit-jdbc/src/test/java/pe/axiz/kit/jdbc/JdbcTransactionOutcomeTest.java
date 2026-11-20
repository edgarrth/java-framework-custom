package pe.axiz.kit.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class JdbcTransactionOutcomeTest {
  enum Failure {
    RESTORE,
    CLOSE,
    COMMIT,
    ROLLBACK
  }

  static final class Fixture {
    int commits;
    int rollbacks;
    int restores;
    int aborts;
    int closes;
    int acquisitions;
    final JdbcTransactions transactions;
    SQLException commitFailure = new SQLException("unknown");

    Fixture(Failure failure) {
      var connection =
          (Connection)
              Proxy.newProxyInstance(
                  Connection.class.getClassLoader(),
                  new Class<?>[] {Connection.class},
                  (proxy, method, arguments) -> {
                    switch (method.getName()) {
                      case "getAutoCommit":
                        return true;
                      case "setAutoCommit":
                        if (Boolean.TRUE.equals(arguments[0])) {
                          restores++;
                          if (failure == Failure.RESTORE) throw new SQLException("restore");
                        }
                        return null;
                      case "commit":
                        commits++;
                        if (failure == Failure.COMMIT) throw commitFailure;
                        return null;
                      case "rollback":
                        rollbacks++;
                        if (failure == Failure.ROLLBACK) throw new SQLException("rollback");
                        return null;
                      case "abort":
                        aborts++;
                        return null;
                      case "close":
                        closes++;
                        if (failure == Failure.CLOSE && closes == 1)
                          throw new SQLException("close");
                        return null;
                      default:
                        throw new UnsupportedOperationException(method.getName());
                    }
                  });
      var source =
          (DataSource)
              Proxy.newProxyInstance(
                  DataSource.class.getClassLoader(),
                  new Class<?>[] {DataSource.class},
                  (proxy, method, arguments) -> {
                    if (!method.getName().equals("getConnection"))
                      throw new UnsupportedOperationException(method.getName());
                    acquisitions++;
                    return connection;
                  });
      transactions = new JdbcTransactions(source);
    }
  }

  @Test
  void confirmedCommitSurvivesRestoreAndCloseFailures() throws Exception {
    for (Failure failure : new Failure[] {Failure.RESTORE, Failure.CLOSE}) {
      var fixture = new Fixture(failure);
      assertEquals("confirmed", fixture.transactions.inTransaction(() -> "confirmed"));
      assertEquals(1, fixture.commits);
      assertEquals(0, fixture.rollbacks);
      assertEquals(1, fixture.aborts);
      fixture.transactions.get();
      assertEquals(2, fixture.acquisitions, "thread binding must be cleared");
    }
  }

  @Test
  void unknownCommitDoesNotRollbackRestoreOrRetry() throws Exception {
    var fixture = new Fixture(Failure.COMMIT);
    assertThrows(
        TransactionOutcomeUnknownException.class,
        () -> fixture.transactions.inTransaction(() -> "possibly committed"));
    assertEquals(1, fixture.commits);
    assertEquals(0, fixture.rollbacks);
    assertEquals(0, fixture.restores);
    assertEquals(1, fixture.aborts);
    fixture.transactions.get();
    assertEquals(2, fixture.acquisitions);
  }

  @Test
  void definitiveServerRollbackKeepsItsClassificationBut40003StaysUnknown() {
    for (String state : new String[] {"40001", "40P01", "23505"}) {
      var fixture = new Fixture(Failure.COMMIT);
      fixture.commitFailure = new SQLException("synthetic server rollback", state);
      assertSame(
          fixture.commitFailure,
          assertThrows(
              SQLException.class, () -> fixture.transactions.inTransaction(() -> "rejected")));
      assertEquals(1, fixture.commits);
      assertEquals(1, fixture.aborts);
      assertEquals(0, fixture.restores);
    }
    var fixture = new Fixture(Failure.COMMIT);
    fixture.commitFailure = new SQLTransactionRollbackException("unknown", "40003");
    assertThrows(
        TransactionOutcomeUnknownException.class,
        () -> fixture.transactions.inTransaction(() -> "unknown"));
  }

  @Test
  void failedRollbackCannotTurnAutocommitBackOn() throws Exception {
    var fixture = new Fixture(Failure.ROLLBACK);
    var original = new IllegalStateException("business failure");
    var actual =
        assertThrows(
            IllegalStateException.class,
            () ->
                fixture.transactions.inTransaction(
                    () -> {
                      throw original;
                    }));
    assertSame(original, actual);
    assertEquals(1, actual.getSuppressed().length);
    assertEquals(0, fixture.commits);
    assertEquals(1, fixture.rollbacks);
    assertEquals(0, fixture.restores);
    assertEquals(1, fixture.aborts);
    fixture.transactions.get();
    assertEquals(2, fixture.acquisitions);
  }
}
