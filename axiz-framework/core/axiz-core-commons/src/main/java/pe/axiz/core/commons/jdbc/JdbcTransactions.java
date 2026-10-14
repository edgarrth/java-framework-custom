package pe.axiz.core.commons.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.Callable;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * Plain JDBC transactions for layer 1 and tests: binds a connection to the current thread for the
 * duration of {@link #inTransaction(Callable)} and hands it out through {@link #get()}.
 */
public final class JdbcTransactions implements TransactionRunner, ConnectionProvider {

  private final DataSource dataSource;
  private final ThreadLocal<@Nullable Connection> bound = new ThreadLocal<>();

  public JdbcTransactions(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource);
  }

  @Override
  public <T> T inTransaction(Callable<T> work) throws Exception {
    if (bound.get() != null) {
      return work.call(); // join the running transaction
    }
    try (Connection c = dataSource.getConnection()) {
      boolean autoCommit = c.getAutoCommit();
      c.setAutoCommit(false);
      bound.set(c);
      try {
        T result = work.call();
        c.commit();
        return result;
      } catch (Exception | Error e) {
        c.rollback();
        throw e;
      } finally {
        bound.remove();
        c.setAutoCommit(autoCommit);
      }
    }
  }

  @Override
  public Connection get() throws SQLException {
    Connection c = bound.get();
    return c != null ? c : dataSource.getConnection();
  }

  @Override
  public void release(Connection connection) throws SQLException {
    if (bound.get() != connection) {
      connection.close();
    }
  }
}
