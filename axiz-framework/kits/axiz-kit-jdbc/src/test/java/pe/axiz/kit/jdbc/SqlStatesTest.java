package pe.axiz.kit.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import pe.axiz.kit.sql.SqlIdentifiers;

class SqlStatesTest {

  @Test
  void aFailedSavepointRollbackPreservesTheSqlFailureAndCannotReportAnIgnoredDuplicate() {
    var original = new SQLException("duplicate", "23505");
    var rollback = new SQLException("savepoint rollback failed", "08006");
    var savepoint =
        (Savepoint)
            Proxy.newProxyInstance(
                Savepoint.class.getClassLoader(),
                new Class<?>[] {Savepoint.class},
                (proxy, method, args) -> 1);
    var connection =
        (Connection)
            Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "getAutoCommit" -> false;
                      case "setSavepoint" -> savepoint;
                      case "rollback" -> throw rollback;
                      default -> throw new AssertionError(method.getName());
                    });
    var insert =
        (PreparedStatement)
            Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                (proxy, method, args) -> {
                  throw original;
                });
    var failure =
        assertThrows(
            SQLException.class, () -> SqlStates.insertIgnoringDuplicate(connection, insert));
    assertSame(original, failure);
    assertEquals(1, failure.getSuppressed().length);
    assertSame(rollback, failure.getSuppressed()[0]);
  }

  @Test
  void detectsDuplicateKeys() {
    assertTrue(SqlStates.isDuplicateKey(new SQLException("dup", "23505")));
    SQLException chained = new SQLException("batch", "XX000");
    chained.setNextException(new SQLException("unique constraint", "23000"));
    assertTrue(SqlStates.isDuplicateKey(chained));
    assertFalse(SqlStates.isDuplicateKey(new SQLException("fk", "23503")));
    assertFalse(SqlStates.isDuplicateKey(new SQLException("x", (String) null)));
  }

  @Test
  void validatesIdentifiers() {
    assertEquals("axiz_outbox", SqlIdentifiers.require("axiz_outbox"));
    assertThrows(IllegalArgumentException.class, () -> SqlIdentifiers.require("x; drop table y"));
    assertThrows(IllegalArgumentException.class, () -> SqlIdentifiers.require("Upper"));
  }

  @Test
  void aForeignKeyWhoseNameContainsUniqueIsNeverTreatedAsADuplicate() {
    assertFalse(
        SqlStates.isDuplicateKey(new SQLException("foreign key fk_unique_accounts", "23503")));
  }

  @Test
  void ignoredDuplicatesUseSavepointsAndLeaveTheBusinessTransactionUsable() throws Exception {
    var source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:sql-state-" + UUID.randomUUID());
    try (var connection = source.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE accounts(id INT PRIMARY KEY CHECK (id>0))");
      try (var insert = connection.prepareStatement("INSERT INTO accounts VALUES (1)")) {
        assertTrue(SqlStates.insertIgnoringDuplicate(connection, insert));
        assertFalse(SqlStates.insertIgnoringDuplicate(connection, insert));
      }
      connection.setAutoCommit(false);
      try (var insert = connection.prepareStatement("INSERT INTO accounts VALUES (2)")) {
        assertTrue(SqlStates.insertIgnoringDuplicate(connection, insert));
        assertFalse(SqlStates.insertIgnoringDuplicate(connection, insert));
      }
      try (var invalid = connection.prepareStatement("INSERT INTO accounts VALUES (-1)")) {
        assertThrows(
            SQLException.class, () -> SqlStates.insertIgnoringDuplicate(connection, invalid));
      }
      try (var rows = statement.executeQuery("SELECT COUNT(*) FROM accounts")) {
        assertTrue(rows.next());
        assertEquals(2, rows.getInt(1));
      }
      connection.commit();
    }
  }
}
