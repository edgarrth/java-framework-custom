package pe.axiz.kit.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.concurrent.Callable;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.tx.TransactionRunner;

/**
 * Plain JDBC transactions for technology adapters and tests: binds a connection to the current
 * thread for the duration of {@link #inTransaction(Callable)} and hands it out through {@link
 * #get()}.
 */
public final class JdbcTransactions implements TransactionRunner, ConnectionProvider {

  /**
   * Connections bound to the current thread, per data source (identity): every instance working on
   * the same data source joins the same transaction.
   */
  private static final ThreadLocal<@Nullable IdentityHashMap<DataSource, Connection>> BOUND =
      new ThreadLocal<>();

  private final DataSource dataSource;

  public JdbcTransactions(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource);
  }

  @Override
  public <T> T inTransaction(Callable<T> work) throws Exception {
    if (bound() != null) {
      return work.call(); // join the running transaction
    }
    try (Connection c = dataSource.getConnection()) {
      boolean autoCommit = c.getAutoCommit();
      c.setAutoCommit(false);
      bind(c);
      @Nullable Throwable primary = null;
      try {
        T result = work.call();
        c.commit();
        return result;
      } catch (Exception | Error failure) {
        primary = failure;
        try {
          c.rollback();
        } catch (SQLException | RuntimeException rollback) {
          if (rollback != failure) failure.addSuppressed(rollback);
        }
        throw failure;
      } finally {
        unbind();
        try {
          c.setAutoCommit(autoCommit);
        } catch (SQLException | RuntimeException restore) {
          if (primary == null) throw restore;
          if (restore != primary) primary.addSuppressed(restore);
        }
      }
    }
  }

  @Override
  public Connection get() throws SQLException {
    Connection c = bound();
    return c != null ? c : dataSource.getConnection();
  }

  @Override
  public void release(Connection connection) throws SQLException {
    if (bound() != connection) {
      connection.close();
    }
  }

  private @Nullable Connection bound() {
    IdentityHashMap<DataSource, Connection> map = BOUND.get();
    return map == null ? null : map.get(dataSource);
  }

  private void bind(Connection c) {
    IdentityHashMap<DataSource, Connection> map = BOUND.get();
    if (map == null) {
      map = new IdentityHashMap<>();
      BOUND.set(map);
    }
    map.put(dataSource, c);
  }

  private void unbind() {
    IdentityHashMap<DataSource, Connection> map = BOUND.get();
    if (map != null) {
      map.remove(dataSource);
      if (map.isEmpty()) {
        BOUND.remove();
      }
    }
  }
}
