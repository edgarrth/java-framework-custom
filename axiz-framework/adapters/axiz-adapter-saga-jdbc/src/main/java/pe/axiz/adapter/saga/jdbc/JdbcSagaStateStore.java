package pe.axiz.adapter.saga.jdbc;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import pe.axiz.core.lock.LockHandle;
import pe.axiz.core.saga.SagaConcurrencyException;
import pe.axiz.core.saga.SagaState;
import pe.axiz.core.saga.SagaStateStore;
import pe.axiz.core.saga.SagaStatus;
import pe.axiz.kit.jdbc.ConnectionProvider;
import pe.axiz.kit.sql.SqlIdentifiers;

/**
 * Saga state on Aurora PostgreSQL with optimistic locking (schema in {@code axiz/sql/saga-*.sql}).
 */
public final class JdbcSagaStateStore implements SagaStateStore {

  private final ConnectionProvider connections;
  private final String table;

  public JdbcSagaStateStore(ConnectionProvider connections, String table) {
    this.connections = Objects.requireNonNull(connections);
    this.table = SqlIdentifiers.require(table);
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
  public void update(SagaState s, String owner) {
    updateState(s, Objects.requireNonNull(owner));
  }

  private void updateState(SagaState s, String owner) {
    int n =
        run(
            "UPDATE "
                + table
                + " SET status = ?, step_index = ?, context = ?, error = ?, updated_at = ?, version"
                + " = ? WHERE saga_id = ? AND version = ?"
                + " AND execution_owner = ? AND execution_until > CURRENT_TIMESTAMP",
            ps -> {
              ps.setString(1, s.status().name());
              ps.setInt(2, s.stepIndex());
              ps.setString(3, s.context());
              ps.setString(4, s.error());
              ps.setTimestamp(5, Timestamp.from(s.updatedAt()));
              ps.setLong(6, s.version());
              ps.setString(7, s.sagaId());
              ps.setLong(8, s.version() - 1);
              ps.setString(9, owner);
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
    if (max < 1 || max > 10000)
      throw new IllegalArgumentException("max must be between 1 and 10000");
    return run(
        "SELECT saga_id, saga_name, status, step_index, context, error, updated_at, version FROM "
            + table
            + " WHERE status IN ('RUNNING', 'COMPENSATING', 'IN_DOUBT') AND updated_at < ?"
            + " AND (execution_owner IS NULL OR execution_until <= CURRENT_TIMESTAMP) ORDER BY"
            + " updated_at FETCH FIRST "
            + max
            + " ROWS ONLY",
        ps -> {
          ps.setTimestamp(1, Timestamp.from(updatedBefore));
          return read(ps);
        });
  }

  @Override
  public Optional<LockHandle> tryAcquire(String resource, Duration lease) {
    positive(lease);
    String owner = UUID.randomUUID().toString();
    int changed =
        run(
            "UPDATE "
                + table
                + " SET execution_owner = ?, execution_until = ? WHERE saga_id = ?"
                + " AND (execution_owner IS NULL OR execution_until <= CURRENT_TIMESTAMP)",
            ps -> {
              ps.setString(1, owner);
              ps.setTimestamp(2, Timestamp.from(databaseNow(ps.getConnection()).plus(lease)));
              ps.setString(3, resource);
              return ps.executeUpdate();
            });
    return changed == 1 ? Optional.of(new Handle(resource, owner)) : Optional.empty();
  }

  private static void positive(Duration lease) {
    if (lease.isNegative() || lease.isZero())
      throw new IllegalArgumentException("Positive lease required");
  }

  private static Instant databaseNow(Connection connection) throws SQLException {
    try (var query = connection.prepareStatement("SELECT CURRENT_TIMESTAMP");
        var rows = query.executeQuery()) {
      if (!rows.next()) throw new SQLException("Database clock unavailable");
      return rows.getTimestamp(1).toInstant();
    }
  }

  private final class Handle implements LockHandle {
    private final String resource;
    private final String owner;

    private Handle(String resource, String owner) {
      this.resource = resource;
      this.owner = owner;
    }

    @Override
    public String resource() {
      return resource;
    }

    @Override
    public String token() {
      return owner;
    }

    @Override
    public boolean renew(Duration lease) {
      positive(lease);
      return run(
              "UPDATE "
                  + table
                  + " SET execution_until = ? WHERE saga_id = ? AND execution_owner = ?"
                  + " AND execution_until > CURRENT_TIMESTAMP",
              ps -> {
                ps.setTimestamp(1, Timestamp.from(databaseNow(ps.getConnection()).plus(lease)));
                ps.setString(2, resource);
                ps.setString(3, owner);
                return ps.executeUpdate();
              })
          == 1;
    }

    @Override
    public void release() {
      run(
          "UPDATE "
              + table
              + " SET execution_owner = NULL, execution_until = NULL"
              + " WHERE saga_id = ? AND execution_owner = ?",
          ps -> {
            ps.setString(1, resource);
            ps.setString(2, owner);
            return ps.executeUpdate();
          });
    }
  }

  private static List<SagaState> read(PreparedStatement ps) throws SQLException {
    List<SagaState> out = new ArrayList<>();
    try (ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        out.add(
            SagaState.builder()
                .sagaId(rs.getString(1))
                .sagaName(rs.getString(2))
                .status(SagaStatus.valueOf(rs.getString(3)))
                .stepIndex(rs.getInt(4))
                .context(rs.getString(5))
                .error(rs.getString(6))
                .updatedAt(rs.getTimestamp(7).toInstant())
                .version(rs.getLong(8))
                .build());
      }
    }
    return out;
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification =
          "Only table identifiers validated by SqlIdentifiers.require enter SQL text; row "
              + "values use PreparedStatement bindings. This private SQL path accepts no "
              + "caller-provided SQL.")
  private <T> T run(String sql, Work<T> work) {
    Connection c = null;
    try {
      c = connections.get();
      if (connections.isTransactionActive(c))
        throw new IllegalStateException(
            "Saga state and ownership require independent auto-commit connections");
      try (PreparedStatement ps = c.prepareStatement(sql)) {
        return work.run(ps);
      }
    } catch (SQLException e) {
      String state = e.getSQLState();
      throw new IllegalStateException(
          "Saga store SQL failed: "
              + (state != null && state.matches("[A-Z0-9]{5}") ? state : "unknown"));
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
