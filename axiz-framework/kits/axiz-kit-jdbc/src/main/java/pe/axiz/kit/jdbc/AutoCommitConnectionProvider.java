package pe.axiz.kit.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Borrows a dedicated connection in auto-commit mode for every operation and closes it on release.
 * Its work never joins, and never waits for, a transaction of the caller: each statement commits on
 * its own. Stores whose writes must be visible to other workers immediately (leases, ownership,
 * process state) use it, so they behave the same inside or outside application transactions.
 */
public final class AutoCommitConnectionProvider implements ConnectionProvider {

  private final DataSource dataSource;

  public AutoCommitConnectionProvider(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public Connection get() throws SQLException {
    Connection c = dataSource.getConnection();
    try {
      if (!c.getAutoCommit()) {
        c.setAutoCommit(true);
      }
      return c;
    } catch (SQLException | RuntimeException e) {
      try {
        c.close();
      } catch (SQLException closing) {
        e.addSuppressed(closing);
      }
      throw e;
    }
  }

  @Override
  public boolean isTransactionActive(Connection connection) {
    return false;
  }

  @Override
  public void release(Connection connection) throws SQLException {
    connection.close();
  }
}
