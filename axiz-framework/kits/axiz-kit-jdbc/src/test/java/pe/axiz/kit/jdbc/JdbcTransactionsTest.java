package pe.axiz.kit.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class JdbcTransactionsTest {
  @Test
  void nestedInstancesShareTheConnectionAndRollbackTogether() throws Exception {
    var source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:kit-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    try (var c = source.getConnection();
        var s = c.createStatement()) {
      s.execute("CREATE TABLE effects(id INT)");
    }
    var outer = new JdbcTransactions(source);
    var inner = new JdbcTransactions(source);
    assertThrows(
        IllegalStateException.class,
        () ->
            outer.inTransaction(
                () -> {
                  var connection = outer.get();
                  assertFalse(connection.getAutoCommit());
                  assertSame(connection, inner.get());
                  inner.inTransaction(
                      () -> {
                        assertSame(connection, inner.get());
                        try (var s = connection.createStatement()) {
                          s.executeUpdate("INSERT INTO effects VALUES(1)");
                        }
                        return true;
                      });
                  outer.release(connection);
                  assertFalse(connection.isClosed());
                  throw new IllegalStateException("rollback");
                }));
    try (var c = source.getConnection();
        var s = c.createStatement();
        var rows = s.executeQuery("SELECT COUNT(*) FROM effects")) {
      assertTrue(rows.next());
      assertEquals(0, rows.getInt(1));
    }
    assertEquals(
        "committed",
        outer.inTransaction(
            () -> {
              try (var s = outer.get().createStatement()) {
                s.executeUpdate("INSERT INTO effects VALUES(2)");
              }
              return "committed";
            }));
    var outside = outer.get();
    assertTrue(outside.getAutoCommit());
    outer.release(outside);
    assertTrue(outside.isClosed());
    try (var c = source.getConnection();
        var s = c.createStatement();
        var rows = s.executeQuery("SELECT COUNT(*) FROM effects")) {
      assertTrue(rows.next());
      assertEquals(1, rows.getInt(1));
    }
  }

  @Test
  void rollbackAndRestoreFailuresCannotEraseTheOriginalBusinessFailure() throws Exception {
    var actual = new JdbcDataSource();
    actual.setURL("jdbc:h2:mem:cleanup-" + UUID.randomUUID());
    DataSource broken =
        (DataSource)
            Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                  try {
                    if (!method.getName().equals("getConnection"))
                      return method.invoke(actual, args);
                    var connection = actual.getConnection();
                    return Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (p, m, a) -> {
                          if (m.getName().equals("rollback"))
                            throw new SQLException("rollback cleanup");
                          if (m.getName().equals("setAutoCommit") && Boolean.TRUE.equals(a[0]))
                            throw new SQLException("restore cleanup");
                          try {
                            return m.invoke(connection, a);
                          } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                          }
                        });
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
    var original = new IllegalStateException("original business failure");
    var failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new JdbcTransactions(broken)
                    .inTransaction(
                        () -> {
                          throw original;
                        }));
    assertSame(original, failure);
    assertEquals(2, failure.getSuppressed().length);
    assertEquals("rollback cleanup", failure.getSuppressed()[0].getMessage());
    assertEquals("restore cleanup", failure.getSuppressed()[1].getMessage());
  }

  @Test
  void independentDataSourcesDoNotJoinAndClosingOnePreservesTheOtherBinding() throws Exception {
    var first = new JdbcDataSource();
    first.setURL("jdbc:h2:mem:first-" + UUID.randomUUID());
    var second = new JdbcDataSource();
    second.setURL("jdbc:h2:mem:second-" + UUID.randomUUID());
    var outer = new JdbcTransactions(first);
    var inner = new JdbcTransactions(second);
    assertTrue(
        outer.inTransaction(
            () -> {
              var firstConnection = outer.get();
              assertTrue(
                  inner.inTransaction(
                      () -> {
                        Assertions.assertNotSame(firstConnection, inner.get());
                        assertFalse(inner.get().getAutoCommit());
                        return true;
                      }));
              assertSame(firstConnection, outer.get());
              return true;
            }));
  }
}
