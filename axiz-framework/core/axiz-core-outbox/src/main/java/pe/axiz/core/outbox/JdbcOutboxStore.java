package pe.axiz.core.outbox;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.jdbc.ConnectionProvider;
import pe.axiz.core.commons.jdbc.SqlStates;

/** JDBC outbox store (Aurora PostgreSQL in production; schema in {@code axiz/sql/outbox.sql}). */
public final class JdbcOutboxStore implements OutboxStore {

  private static final int MAX_ERROR = 1000;

  private final ConnectionProvider connections;
  private final String table;
  private final Dialect dialect;

  public JdbcOutboxStore(ConnectionProvider connections, String table, Dialect dialect) {
    this.connections = Objects.requireNonNull(connections);
    this.table = SqlStates.identifier(table);
    this.dialect = Objects.requireNonNull(dialect);
  }

  @Override
  public void append(OutboxRecord r) {
    String sql =
        "INSERT INTO "
            + table
            + " (id, destination, aggregate_type, aggregate_id, event_type, envelope, status,"
            + " attempts, created_at, next_attempt_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    execute(
        sql,
        ps -> {
          ps.setString(1, r.id());
          ps.setString(2, r.destination());
          ps.setString(3, r.aggregateType());
          ps.setString(4, r.aggregateId());
          ps.setString(5, r.eventType());
          ps.setString(6, r.envelopeJson());
          ps.setString(7, r.status().name());
          ps.setInt(8, r.attempts());
          ps.setTimestamp(9, Timestamp.from(r.createdAt()));
          ps.setTimestamp(10, Timestamp.from(r.nextAttemptAt()));
          return ps.executeUpdate();
        });
  }

  @Override
  public List<OutboxRecord> lockDue(int size, Instant now) {
    String sql =
        "SELECT id, destination, aggregate_type, aggregate_id, event_type, envelope, status,"
            + " attempts, created_at, next_attempt_at, last_error FROM "
            + table
            + " WHERE status = 'PENDING' AND next_attempt_at <= ? ORDER BY created_at, id FETCH"
            + " FIRST "
            + size
            + " ROWS ONLY"
            + dialect.lockClause();
    return execute(
        sql,
        ps -> {
          ps.setTimestamp(1, Timestamp.from(now));
          List<OutboxRecord> out = new ArrayList<>();
          try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
              out.add(
                  new OutboxRecord(
                      rs.getString(1),
                      rs.getString(2),
                      rs.getString(3),
                      rs.getString(4),
                      rs.getString(5),
                      rs.getString(6),
                      OutboxRecord.Status.valueOf(rs.getString(7)),
                      rs.getInt(8),
                      rs.getTimestamp(9).toInstant(),
                      rs.getTimestamp(10).toInstant(),
                      rs.getString(11)));
            }
          }
          return out;
        });
  }

  @Override
  public void markPublished(String id, Instant at) {
    execute(
        "UPDATE "
            + table
            + " SET status = 'PUBLISHED', published_at = ?, last_error = NULL WHERE id = ?",
        ps -> {
          ps.setTimestamp(1, Timestamp.from(at));
          ps.setString(2, id);
          return ps.executeUpdate();
        });
  }

  @Override
  public void markFailed(
      String id, int attempts, Instant nextAttemptAt, @Nullable String error, boolean giveUp) {
    execute(
        "UPDATE "
            + table
            + " SET status = ?, attempts = ?, next_attempt_at = ?, last_error = ? WHERE id = ?",
        ps -> {
          ps.setString(
              1, giveUp ? OutboxRecord.Status.FAILED.name() : OutboxRecord.Status.PENDING.name());
          ps.setInt(2, attempts);
          ps.setTimestamp(3, Timestamp.from(nextAttemptAt));
          ps.setString(
              4,
              error == null
                  ? null
                  : (error.length() > MAX_ERROR ? error.substring(0, MAX_ERROR) : error));
          ps.setString(5, id);
          return ps.executeUpdate();
        });
  }

  @Override
  public int purgePublished(Instant before) {
    return execute(
        "DELETE FROM " + table + " WHERE status = 'PUBLISHED' AND published_at < ?",
        ps -> {
          ps.setTimestamp(1, Timestamp.from(before));
          return ps.executeUpdate();
        });
  }

  @Override
  public long countPending() {
    return execute(
        "SELECT COUNT(*) FROM " + table + " WHERE status = 'PENDING'",
        ps -> {
          try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
          }
        });
  }

  private <T> T execute(String sql, StatementWork<T> work) {
    Connection c = null;
    try {
      c = connections.get();
      try (PreparedStatement ps = c.prepareStatement(sql)) {
        return work.run(ps);
      }
    } catch (SQLException e) {
      throw new OutboxException("Outbox SQL failed: " + e.getSQLState(), e);
    } finally {
      if (c != null) {
        try {
          connections.release(c);
        } catch (SQLException ignored) {
          // connection release failures are not actionable here
        }
      }
    }
  }

  @FunctionalInterface
  private interface StatementWork<T> {
    T run(PreparedStatement ps) throws SQLException;
  }
}
