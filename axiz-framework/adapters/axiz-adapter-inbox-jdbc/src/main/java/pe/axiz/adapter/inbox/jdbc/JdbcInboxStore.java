package pe.axiz.adapter.inbox.jdbc;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.inbox.InboxException;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.kit.jdbc.ConnectionProvider;
import pe.axiz.kit.jdbc.SqlStates;
import pe.axiz.kit.sql.SqlIdentifiers;

/**
 * JDBC inbox (Aurora PostgreSQL). Acquisition is an INSERT on the primary key; a duplicate key
 * means the message was seen, unless the previous attempt left an expired in-progress lease, which
 * is taken over with a new owner. Completion and release are conditioned on the owner.
 */
public final class JdbcInboxStore implements InboxStore {

  public ConnectionProvider connectionProvider() {
    return connections;
  }

  private final ConnectionProvider connections;
  private final String table;

  public JdbcInboxStore(ConnectionProvider connections, String table) {
    this.connections = Objects.requireNonNull(connections);
    this.table = SqlIdentifiers.require(table);
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification =
          "Only table identifiers validated by SqlIdentifiers.require enter SQL text; row "
              + "values use PreparedStatement bindings. This private SQL path accepts no "
              + "caller-provided SQL.")
  @Override
  public Acquisition tryAcquire(
      String consumer,
      String messageId,
      String owner,
      Instant now,
      Instant leaseUntil,
      Instant expiresAt) {
    InboxStore.validateWindow(now, leaseUntil, expiresAt);
    Connection c = null;
    try {
      c = connections.get();
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO "
                  + table
                  + " (consumer, message_id, status, owner, received_at, lease_until, expires_at)"
                  + " VALUES (?, ?, 'IN_PROGRESS', ?, ?, ?, ?)")) {
        ps.setString(1, consumer);
        ps.setString(2, messageId);
        ps.setString(3, owner);
        ps.setTimestamp(4, Timestamp.from(now));
        ps.setTimestamp(5, Timestamp.from(leaseUntil));
        ps.setTimestamp(6, Timestamp.from(expiresAt));
        if (SqlStates.insertIgnoringDuplicate(c, ps)) {
          return Acquisition.acquired();
        }
      }
      // take over an expired in-progress registration (its delivery died)
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE "
                  + table
                  + " SET owner = ?, lease_until = ?, received_at = ?, expires_at = ? WHERE consumer = ? AND"
                  + " message_id = ? AND status = 'IN_PROGRESS' AND lease_until < ?")) {
        ps.setString(1, owner);
        ps.setTimestamp(2, Timestamp.from(leaseUntil));
        ps.setTimestamp(3, Timestamp.from(now));
        ps.setTimestamp(4, Timestamp.from(expiresAt));
        ps.setString(5, consumer);
        ps.setString(6, messageId);
        ps.setTimestamp(7, Timestamp.from(now));
        if (ps.executeUpdate() == 1) {
          return Acquisition.acquired();
        }
      }
      try (PreparedStatement ps =
          c.prepareStatement(
              "SELECT status, lease_until FROM "
                  + table
                  + " WHERE consumer = ? AND message_id = ?")) {
        ps.setString(1, consumer);
        ps.setString(2, messageId);
        try (ResultSet rs = ps.executeQuery()) {
          if (!rs.next()) {
            return Acquisition.busy(now); // released meanwhile: redeliver right away
          }
          if ("PROCESSED".equals(rs.getString(1))) {
            return Acquisition.processed();
          }
          Timestamp until = rs.getTimestamp(2);
          return Acquisition.busy(until == null ? null : until.toInstant());
        }
      }
    } catch (SQLException e) {
      throw new InboxException("Inbox acquire failed: " + e.getSQLState(), e);
    } finally {
      close(c);
    }
  }

  @Override
  public boolean markProcessed(String consumer, String messageId, String owner, Instant at) {
    return update(
            "UPDATE "
                + table
                + " SET status = 'PROCESSED', processed_at = ? WHERE consumer = ? AND message_id ="
                + " ? AND owner = ? AND status = 'IN_PROGRESS' AND lease_until > ? AND expires_at > ?",
            ps -> {
              ps.setTimestamp(1, Timestamp.from(at));
              ps.setString(2, consumer);
              ps.setString(3, messageId);
              ps.setString(4, owner);
              ps.setTimestamp(5, Timestamp.from(at));
              ps.setTimestamp(6, Timestamp.from(at));
            })
        == 1;
  }

  @Override
  public boolean release(String consumer, String messageId, String owner) {
    return update(
            "DELETE FROM "
                + table
                + " WHERE consumer = ? AND message_id = ? AND owner = ? AND status = 'IN_PROGRESS'",
            ps -> {
              ps.setString(1, consumer);
              ps.setString(2, messageId);
              ps.setString(3, owner);
            })
        == 1;
  }

  @Override
  public int purgeExpired(Instant now) {
    return update(
        "DELETE FROM " + table + " WHERE expires_at < ? AND status <> 'IN_PROGRESS'",
        ps -> ps.setTimestamp(1, Timestamp.from(now)));
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification =
          "Only table identifiers validated by SqlIdentifiers.require enter SQL text; row "
              + "values use PreparedStatement bindings. This private SQL path accepts no "
              + "caller-provided SQL.")
  private int update(String sql, StatementBindings bindings) {
    Connection c = null;
    try {
      c = connections.get();
      try (PreparedStatement ps = c.prepareStatement(sql)) {
        bindings.apply(ps);
        return ps.executeUpdate();
      }
    } catch (SQLException e) {
      throw new InboxException("Inbox update failed: " + e.getSQLState(), e);
    } finally {
      close(c);
    }
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification =
          "Only table identifiers validated by SqlIdentifiers.require enter SQL text; row "
              + "values use PreparedStatement bindings. This private SQL path accepts no "
              + "caller-provided SQL.")
  @Override
  public void verifySchema() {
    Connection c = null;
    try {
      c = connections.get();
      try (PreparedStatement ps =
          c.prepareStatement(
              "SELECT consumer, message_id, status, owner, lease_until, expires_at FROM "
                  + table
                  + " WHERE 1 = 0")) {
        ps.executeQuery().close();
      }
    } catch (SQLException e) {
      throw new InboxException(SCHEMA_HINT.formatted(table), e);
    } finally {
      close(c);
    }
  }

  @FunctionalInterface
  private interface StatementBindings {
    void apply(PreparedStatement statement) throws SQLException;
  }

  /** Message of a missing or outdated inbox table. */
  static final String SCHEMA_HINT =
      "Inbox table %s does not match the current v2 schema (owner required): "
          + "see axiz/sql/inbox-postgresql.sql and manage schema with reviewed migrations";

  private void close(@Nullable Connection c) {
    if (c != null) {
      try {
        connections.release(c);
      } catch (SQLException ignored) {
        // not actionable
      }
    }
  }
}
