package pe.axiz.adapter.outbox.jdbc;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.audit.AuditChainState;
import pe.axiz.core.audit.AuditIntegrity;
import pe.axiz.core.audit.AuditPublishException;
import pe.axiz.core.audit.ChainCheckpoint;
import pe.axiz.kit.jdbc.ConnectionProvider;
import pe.axiz.kit.sql.SqlIdentifiers;

/** A chain head locked in the caller's JDBC transaction, committed atomically with its outbox. */
@SuppressFBWarnings(
    value = "SQL_INJECTION_JDBC",
    justification =
        "The table identifier passes SqlIdentifiers.require; all data is parameter-bound.")
public final class JdbcAuditChainState implements AuditChainState {
  private final ConnectionProvider transactions;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Borrowed pool must retain the identity of the enlisted transaction resource.")
  private final DataSource committedConnections;

  private final String table;
  private final Dialect dialect;
  private final Clock clock;
  private final int queryTimeoutSeconds;

  public JdbcAuditChainState(
      ConnectionProvider transactions,
      DataSource committedConnections,
      String table,
      Dialect dialect,
      Clock clock,
      Duration statementTimeout) {
    this.transactions = Objects.requireNonNull(transactions);
    this.committedConnections = Objects.requireNonNull(committedConnections);
    if (transactions.transactionResource() != committedConnections)
      throw new IllegalArgumentException("Audit chain must use its transaction resource");
    this.table = SqlIdentifiers.require(table);
    this.dialect = Objects.requireNonNull(dialect);
    this.clock = Objects.requireNonNull(clock);
    if (!statementTimeout.isPositive() || statementTimeout.compareTo(Duration.ofMinutes(1)) > 0)
      throw new IllegalArgumentException(
          "Audit statement timeout must be positive and at most one minute");
    this.queryTimeoutSeconds = (int) ((statementTimeout.toNanos() + 999_999_999L) / 1_000_000_000L);
  }

  @Override
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Port exposes resource identity so composition can reject non-atomic stores.")
  public Object transactionResource() {
    return committedConnections;
  }

  @Override
  public AuditIntegrity advance(String chainId, UnaryOperator<AuditIntegrity> step) {
    AuditIntegrity initial = AuditIntegrity.initial(chainId);
    try (var lease = new ConnectionLease(transactions, transactions.get())) {
      Connection connection = lease.connection();
      if (!transactions.isTransactionActive(connection))
        throw new IllegalStateException("Audit chain advance requires an active transaction");
      String initialize =
          dialect == Dialect.POSTGRESQL
              ? "INSERT INTO "
                  + table
                  + " (chain_id,last_sequence,last_hash,updated_at) VALUES (?,?,?,?)"
                  + " ON CONFLICT (chain_id) DO NOTHING"
              : "MERGE INTO "
                  + table
                  + " c USING (VALUES (?,?,?,?)) v(chain_id,last_sequence,last_hash,updated_at)"
                  + " ON c.chain_id=v.chain_id WHEN NOT MATCHED THEN INSERT"
                  + " (chain_id,last_sequence,last_hash,updated_at)"
                  + " VALUES (v.chain_id,v.last_sequence,v.last_hash,v.updated_at)";
      try (var initialization =
              new InitializationSavepoint(
                  connection, dialect == Dialect.H2 ? connection.setSavepoint() : null);
          var statement = connection.prepareStatement(initialize)) {
        statement.setQueryTimeout(queryTimeoutSeconds);
        statement.setString(1, chainId);
        statement.setLong(2, 0);
        statement.setString(3, initial.hash());
        statement.setTimestamp(4, Timestamp.from(clock.instant()));
        try {
          statement.executeUpdate();
        } catch (SQLException concurrentInsert) {
          // H2 MERGE can race on a new chain. Undo only this initializer, never business work.
          initialization.recoverConcurrentInsert(concurrentInsert);
        }
      }
      AuditIntegrity head;
      try (var statement =
          connection.prepareStatement(
              "SELECT last_sequence,last_hash FROM " + table + " WHERE chain_id=? FOR UPDATE")) {
        statement.setQueryTimeout(queryTimeoutSeconds);
        statement.setString(1, chainId);
        try (var rows = statement.executeQuery()) {
          if (!rows.next()) throw new IllegalStateException("Audit chain head is missing");
          head = AuditIntegrity.head(chainId, rows.getLong(1), rows.getString(2));
        }
      }
      AuditIntegrity next = Objects.requireNonNull(step.apply(head), "chain successor");
      head.requireSuccessor(next);
      try (var statement =
          connection.prepareStatement(
              "UPDATE "
                  + table
                  + " SET last_sequence=?,last_hash=?,updated_at=? WHERE chain_id=?")) {
        statement.setQueryTimeout(queryTimeoutSeconds);
        statement.setLong(1, next.sequence());
        statement.setString(2, next.hash());
        statement.setTimestamp(3, Timestamp.from(clock.instant()));
        statement.setString(4, chainId);
        if (statement.executeUpdate() != 1)
          throw new IllegalStateException("Audit chain head update failed");
      }
      return next;
    } catch (SQLException failure) {
      throw failure(failure);
    }
  }

  private record InitializationSavepoint(Connection connection, @Nullable Savepoint savepoint)
      implements AutoCloseable {
    void recoverConcurrentInsert(SQLException failure) throws SQLException {
      if (savepoint == null || !"23505".equals(failure.getSQLState())) throw failure;
      connection.rollback(savepoint);
    }

    @Override
    public void close() throws SQLException {
      if (savepoint != null) connection.releaseSavepoint(savepoint);
    }
  }

  private record ConnectionLease(ConnectionProvider provider, Connection connection)
      implements AutoCloseable {
    @Override
    public void close() throws SQLException {
      provider.release(connection);
    }
  }

  @Override
  public ChainCheckpoint checkpoint(String chainId) {
    AuditIntegrity initial = AuditIntegrity.initial(chainId);
    // Deliberately bypass the transaction-bound provider: an uncommitted head is not evidence.
    try (Connection connection = committedConnections.getConnection()) {
      if (!connection.getAutoCommit())
        throw new IllegalStateException(
            "Audit checkpoint requires an independent autocommit connection");
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      try (var statement =
          connection.prepareStatement(
              "SELECT last_sequence,last_hash,updated_at FROM " + table + " WHERE chain_id=?")) {
        statement.setQueryTimeout(queryTimeoutSeconds);
        statement.setString(1, chainId);
        try (var rows = statement.executeQuery()) {
          return rows.next()
              ? new ChainCheckpoint(chainId, rows.getLong(1), rows.getString(2), clock.instant())
              : new ChainCheckpoint(chainId, 0, initial.hash(), clock.instant());
        }
      }
    } catch (SQLException failure) {
      throw failure(failure);
    }
  }

  private static AuditPublishException failure(SQLException failure) {
    return new AuditPublishException("Audit chain database operation failed", failure);
  }
}
