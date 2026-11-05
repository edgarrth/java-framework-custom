package pe.axiz.kit.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class SqlStatesTest {

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
    assertEquals("axiz_outbox", SqlStates.identifier("axiz_outbox"));
    assertThrows(IllegalArgumentException.class, () -> SqlStates.identifier("x; drop table y"));
    assertThrows(IllegalArgumentException.class, () -> SqlStates.identifier("Upper"));
  }

  @Test
  void aForeignKeyWhoseNameContainsUniqueIsNeverTreatedAsADuplicate() {
    assertFalse(
        SqlStates.isDuplicateKey(new SQLException("foreign key fk_unique_accounts", "23503")));
  }

  @Test
  void ignoredDuplicatesUseSavepointsAndLeaveTheBusinessTransactionUsable() throws Exception {
    var source = new org.h2.jdbcx.JdbcDataSource();
    source.setURL("jdbc:h2:mem:sql-state-" + java.util.UUID.randomUUID());
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
