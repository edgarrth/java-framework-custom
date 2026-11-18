package pe.axiz.kit.jdbc;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.concurrent.Callable;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
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

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "DataSource is a borrowed collaborator; sharing its identity preserves lifecycle, "
              + "transactions or instrumentation. The field is not an owned mutable value.")
  private final DataSource dataSource;

  public JdbcTransactions(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource);
  }

  @Override
  public <T> T inTransaction(Callable<T> work) throws Exception {
    if (bound() != null) {
      return work.call(); // join the running transaction
    }
    Connection connection = dataSource.getConnection();
    boolean autoCommit = true;
    boolean restore = false;
    boolean poisoned = false;
    @Nullable Throwable primary = null;
    try {
      autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      restore = true;
      bind(connection);
      T result;
      try {
        result = work.call();
      } catch (Exception | Error failure) {
        try {
          connection.rollback();
        } catch (SQLException | RuntimeException rollback) {
          poisoned = true;
          if (rollback != failure) failure.addSuppressed(rollback);
        }
        throw failure;
      }
      try {
        connection.commit();
      } catch (SQLException | RuntimeException commit) {
        poisoned = true;
        throw new TransactionOutcomeUnknownException(commit);
      }
      return result;
    } catch (Exception | Error failure) {
      primary = failure;
      if (!restore) poisoned = true;
      throw failure;
    } finally {
      unbind();
      if (restore && !poisoned) {
        try {
          connection.setAutoCommit(autoCommit);
        } catch (SQLException | RuntimeException failure) {
          poisoned = true;
          cleanupFailure(primary, failure);
        }
      }
      if (!poisoned) {
        try {
          connection.close();
        } catch (SQLException | RuntimeException failure) {
          poisoned = true;
          cleanupFailure(primary, failure);
        }
      }
      if (poisoned) {
        try {
          connection.abort(Runnable::run);
        } catch (SQLException | RuntimeException failure) {
          cleanupFailure(primary, failure);
        } finally {
          try {
            connection.close();
          } catch (SQLException | RuntimeException failure) {
            cleanupFailure(primary, failure);
          }
        }
      }
    }
  }

  private static void cleanupFailure(@Nullable Throwable primary, Throwable failure) {
    if (primary != null && primary != failure) primary.addSuppressed(failure);
    // Driver exception messages can contain SQL and values; log only the cleanup event.
    LoggerFactory.getLogger(JdbcTransactions.class)
        .warn(
            "JDBC transaction cleanup failed; discard the connection; do not retry confirmed"
                + " commits");
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
