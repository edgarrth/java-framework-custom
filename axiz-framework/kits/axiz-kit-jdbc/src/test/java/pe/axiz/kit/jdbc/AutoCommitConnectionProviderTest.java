package pe.axiz.kit.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class AutoCommitConnectionProviderTest {

  @Test
  void commitsOnItsOwnConnectionWhileTheCallerTransactionRollsBack() throws Exception {
    var source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:autocommit-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    try (var c = source.getConnection();
        var s = c.createStatement()) {
      s.execute("CREATE TABLE leases(id INT)");
    }
    var transactions = new JdbcTransactions(source);
    var independent = new AutoCommitConnectionProvider(source);
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.inTransaction(
                () -> {
                  var callers = transactions.get();
                  var own = independent.get();
                  try {
                    assertNotSame(callers, own);
                    assertTrue(own.getAutoCommit());
                    assertFalse(independent.isTransactionActive(own));
                    try (var s = own.createStatement()) {
                      s.executeUpdate("INSERT INTO leases VALUES (1)");
                    }
                  } finally {
                    independent.release(own);
                  }
                  assertTrue(own.isClosed());
                  throw new IllegalStateException("caller fails");
                }));
    try (var c = source.getConnection();
        var s = c.createStatement();
        var r = s.executeQuery("SELECT COUNT(*) FROM leases")) {
      r.next();
      assertEquals(1, r.getInt(1));
    }
  }

  @Test
  void switchesPooledConnectionsBackToAutoCommit() throws Exception {
    var source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:autocommit-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    var manual =
        (javax.sql.DataSource)
            java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {javax.sql.DataSource.class},
                (proxy, method, args) ->
                    method.getName().equals("getConnection")
                        ? manualCommit(source)
                        : method.invoke(source, args));
    var provider = new AutoCommitConnectionProvider(manual);
    var c = provider.get();
    assertTrue(c.getAutoCommit());
    provider.release(c);
  }

  private static java.sql.Connection manualCommit(JdbcDataSource source) {
    try {
      var c = source.getConnection();
      c.setAutoCommit(false);
      return c;
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
