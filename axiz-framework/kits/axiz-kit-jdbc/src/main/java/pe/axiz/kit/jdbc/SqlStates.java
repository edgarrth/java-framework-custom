package pe.axiz.kit.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.Locale;

/** SQLSTATE helpers (portable across PostgreSQL and H2). */
public final class SqlStates {

  private SqlStates() {}

  /** Unique constraint violation (SQLSTATE 23505, integrity class 23). */
  public static boolean isDuplicateKey(SQLException e) {
    for (SQLException ex = e; ex != null; ex = ex.getNextException()) {
      String state = ex.getSQLState();
      if ("23505".equals(state)
          || ("23000".equals(state)
              && ex.getMessage() != null
              && ex.getMessage().toLowerCase(Locale.ROOT).contains("unique"))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Executes an INSERT and reports {@code false} on a duplicate key. Inside a transaction a
   * savepoint is used, because PostgreSQL aborts the whole transaction after any error.
   *
   * @param connection connection to use
   * @param insert prepared INSERT statement (parameters already set)
   * @return true when inserted, false when the key already existed
   * @throws SQLException any other error
   */
  public static boolean insertIgnoringDuplicate(Connection connection, PreparedStatement insert)
      throws SQLException {
    Savepoint savepoint = connection.getAutoCommit() ? null : connection.setSavepoint();
    try {
      insert.executeUpdate();
      if (savepoint != null) {
        connection.releaseSavepoint(savepoint);
      }
      return true;
    } catch (SQLException e) {
      if (savepoint != null) {
        connection.rollback(savepoint);
      }
      if (isDuplicateKey(e)) {
        return false;
      }
      throw e;
    }
  }

  /** Validates an SQL identifier that comes from configuration (table or sequence name). */
  public static String identifier(String name) {
    if (!name.matches("^[a-z_][a-z0-9_]{0,62}$")) {
      throw new IllegalArgumentException("Invalid SQL identifier: " + name);
    }
    return name;
  }
}
