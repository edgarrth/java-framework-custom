package pe.axiz.kit.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.Locale;

/** Utilidades de SQLSTATE (portables entre PostgreSQL y H2). */
public final class SqlStates {

  private SqlStates() {}

  /** Violación de restricción única (SQLSTATE 23505, clase de integridad 23). */
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
   * Ejecuta un INSERT e informa {@code false} ante una clave duplicada. Dentro de una transacción
   * se usa un savepoint, porque PostgreSQL aborta toda la transacción tras cualquier error.
   *
   * @param connection conexión a usar
   * @param insert sentencia INSERT preparada (con los parámetros ya fijados)
   * @return true cuando se insertó, false cuando la clave ya existía
   * @throws SQLException cualquier otro error
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
        try {
          connection.rollback(savepoint);
        } catch (SQLException | RuntimeException rollback) {
          if (rollback != e) e.addSuppressed(rollback);
          throw e;
        }
      }
      if (isDuplicateKey(e)) {
        return false;
      }
      throw e;
    }
  }
}
