package pe.axiz.core.saga;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import pe.axiz.core.commons.jdbc.ConnectionProvider;
import pe.axiz.core.commons.jdbc.SqlStates;

/**
 * Saga state on Aurora PostgreSQL with optimistic locking (schema in {@code axiz/sql/saga-*.sql}).
 */
public final class JdbcSagaStateStore implements SagaStateStore {

  private final ConnectionProvider connections;
  private final String table;

  public JdbcSagaStateStore(ConnectionProvider connections, String table) {
    this.connections = Objects.requireNonNull(connections);
    this.table = SqlStates.identifier(table);
  }

  @Override
  public void create(SagaState s) {
    run(
        "INSERT INTO "
            + table
            + " (saga_id, saga_name, status, step_index, context, error, updated_at, version)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        ps -> {
          ps.setString(1, s.sagaId());
          ps.setString(2, s.sagaName());
          ps.setString(3, s.status().name());
          ps.setInt(4, s.stepIndex());
          ps.setString(5, s.context());
          ps.setString(6, s.error());
          ps.setTimestamp(7, Timestamp.from(s.updatedAt()));
          ps.setLong(8, s.version());
          return ps.executeUpdate();
        });
  }

  @Override
  public void update(SagaState s) {
    int n =
        run(
            "UPDATE "
                + table
                + " SET status = ?, step_index = ?, context = ?, error = ?, updated_at = ?, version"
                + " = ? WHERE saga_id = ? AND version = ?",
            ps -> {
              ps.setString(1, s.status().name());
              ps.setInt(2, s.stepIndex());
              ps.setString(3, s.context());
              ps.setString(4, s.error());
              ps.setTimestamp(5, Timestamp.from(s.updatedAt()));
              ps.setLong(6, s.version());
              ps.setString(7, s.sagaId());
              ps.setLong(8, s.version() - 1);
              return ps.executeUpdate();
            });
    if (n != 1) {
      throw new SagaConcurrencyException(s.sagaId());
    }
  }

  @Override
  public Optional<SagaState> find(String sagaId) {
    return run(
        "SELECT saga_id, saga_name, status, step_index, context, error, updated_at, version FROM "
            + table
            + " WHERE saga_id = ?",
        ps -> {
          ps.setString(1, sagaId);
          List<SagaState> l = read(ps);
          return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
        });
  }

  @Override
  public List<SagaState> findStale(Instant updatedBefore, int max) {
    return run(
        "SELECT saga_id, saga_name, status, step_index, context, error, updated_at, version FROM "
            + table
            + " WHERE status IN ('RUNNING', 'COMPENSATING', 'IN_DOUBT') AND updated_at < ? ORDER BY"
            + " updated_at FETCH FIRST "
            + max
            + " ROWS ONLY",
        ps -> {
          ps.setTimestamp(1, Timestamp.from(updatedBefore));
          return read(ps);
        });
  }

  private static List<SagaState> read(PreparedStatement ps) throws SQLException {
    List<SagaState> out = new ArrayList<>();
    try (ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        out.add(
            new SagaState(
                rs.getString(1),
                rs.getString(2),
                SagaStatus.valueOf(rs.getString(3)),
                rs.getInt(4),
                rs.getString(5),
                rs.getString(6),
                rs.getTimestamp(7).toInstant(),
                rs.getLong(8)));
      }
    }
    return out;
  }

  private <T> T run(String sql, Work<T> work) {
    Connection c = null;
    try {
      c = connections.get();
      try (PreparedStatement ps = c.prepareStatement(sql)) {
        return work.run(ps);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Saga store SQL failed: " + e.getSQLState(), e);
    } finally {
      if (c != null) {
        try {
          connections.release(c);
        } catch (SQLException ignored) {
          // not actionable
        }
      }
    }
  }

  @FunctionalInterface
  private interface Work<T> {
    T run(PreparedStatement ps) throws SQLException;
  }
}
