package pe.axiz.kit.jdbc;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLTransactionRollbackException;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.concurrent.Callable;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import pe.axiz.core.commons.tx.TransactionRunner;

/**
 * Transacciones JDBC simples para adaptadores tecnológicos y pruebas: liga una conexión al hilo
 * actual durante {@link #inTransaction(Callable)} y la entrega mediante {@link #get()}.
 */
public final class JdbcTransactions implements TransactionRunner, ConnectionProvider {

  private static boolean rejectedCommit(SQLException failure) {
    String state = failure.getSQLState();
    if ("40003".equals(state)) return false;
    return failure instanceof SQLTransactionRollbackException
        || failure instanceof SQLIntegrityConstraintViolationException
        || "40001".equals(state)
        || "40P01".equals(state)
        || (state != null && state.startsWith("23"));
  }

  /**
   * Conexiones ligadas al hilo actual, por data source (identidad): todas las instancias que
   * trabajan sobre el mismo data source se unen a la misma transacción.
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
      return work.call(); // se une a la transacción en curso
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
        if (commit instanceof SQLException sql && rejectedCommit(sql)) throw sql;
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
    // Los mensajes de excepción del driver pueden contener SQL y valores; solo se registra en log
    // el evento de limpieza.
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
