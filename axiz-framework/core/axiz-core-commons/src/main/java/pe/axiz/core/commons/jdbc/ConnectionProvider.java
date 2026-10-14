package pe.axiz.core.commons.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Supplies the JDBC connection to use. Inside a transaction it returns the transaction's connection
 * (Spring: {@code DataSourceUtils}); {@link #release(Connection)} must not close it.
 */
public interface ConnectionProvider {

  Connection get() throws SQLException;

  void release(Connection connection) throws SQLException;
}
