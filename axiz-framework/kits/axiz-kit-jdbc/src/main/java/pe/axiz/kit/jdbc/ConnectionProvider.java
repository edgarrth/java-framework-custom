package pe.axiz.kit.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import org.jspecify.annotations.Nullable;

/**
 * Supplies the JDBC connection to use. Inside a transaction it returns the transaction's connection
 * (Spring: {@code DataSourceUtils}); {@link #release(Connection)} must not close it.
 */
public interface ConnectionProvider {

  /**
   * Identity of the resource enlisted in transactions; null for providers without that contract.
   */
  default @Nullable Object transactionResource() {
    return null;
  }

  /** Whether this connection participates in the caller's active transaction. */
  default boolean isTransactionActive(Connection connection) throws SQLException {
    return !connection.getAutoCommit();
  }

  Connection get() throws SQLException;

  void release(Connection connection) throws SQLException;
}
