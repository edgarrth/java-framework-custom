package pe.axiz.adapter.sequence.jdbc;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.SqlIdentifiers;
import pe.axiz.core.commons.tx.TransactionRunner;
import pe.axiz.core.sequence.SequenceStore;
import pe.axiz.kit.jdbc.ConnectionProvider;
import pe.axiz.kit.jdbc.SqlStates;

/**
 * Counter table on Aurora PostgreSQL: {@code UPDATE ... SET value = value + ?} locks the row, so
 * concurrent replicas never get the same value; the first use of a period inserts the row. Works
 * with any SQL database (H2 in tests).
 */
public final class JdbcSequenceStore implements SequenceStore {

  private final ConnectionProvider connections;
  private final TransactionRunner transactions;
  private final String table;

  public JdbcSequenceStore(
      ConnectionProvider connections, TransactionRunner transactions, String table) {
    this.connections = Objects.requireNonNull(connections);
    this.transactions = Objects.requireNonNull(transactions);
    this.table = SqlIdentifiers.require(table);
  }

  @Override
  public long increment(String name, String period, int increment) {
    try {
      return transactions.inTransaction(
          () -> {
            for (int attempt = 0; attempt < 3; attempt++) {
              Long value = tryUpdate(name, period, increment);
              if (value != null) {
                return value;
              }
              if (tryInsert(name, period, increment)) {
                return (long) increment;
              }
            }
            throw new IllegalStateException("Could not allocate sequence " + name);
          });
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Sequence allocation failed", e);
    }
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification =
          "Only table identifiers validated by SqlIdentifiers.require enter SQL text; row "
              + "values use PreparedStatement bindings. This private SQL path accepts no "
              + "caller-provided SQL.")
  private @Nullable Long tryUpdate(String name, String period, int increment) throws SQLException {
    Connection c = connections.get();
    try {
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE "
                  + table
                  + " SET seq_value = seq_value + ? WHERE seq_name = ? AND seq_period = ?")) {
        ps.setInt(1, increment);
        ps.setString(2, name);
        ps.setString(3, period);
        if (ps.executeUpdate() == 0) {
          return null;
        }
      }
      try (PreparedStatement ps =
          c.prepareStatement(
              "SELECT seq_value FROM " + table + " WHERE seq_name = ? AND seq_period = ?")) {
        ps.setString(1, name);
        ps.setString(2, period);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          return rs.getLong(1);
        }
      }
    } finally {
      connections.release(c);
    }
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification =
          "Only table identifiers validated by SqlIdentifiers.require enter SQL text; row "
              + "values use PreparedStatement bindings. This private SQL path accepts no "
              + "caller-provided SQL.")
  private boolean tryInsert(String name, String period, int increment) throws SQLException {
    Connection c = connections.get();
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO " + table + " (seq_name, seq_period, seq_value) VALUES (?, ?, ?)")) {
      ps.setString(1, name);
      ps.setString(2, period);
      ps.setLong(3, increment);
      return SqlStates.insertIgnoringDuplicate(c, ps);
    } finally {
      connections.release(c);
    }
  }
}
