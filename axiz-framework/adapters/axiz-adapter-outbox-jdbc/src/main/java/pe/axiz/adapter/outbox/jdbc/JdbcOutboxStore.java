package pe.axiz.adapter.outbox.jdbc;

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
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.SqlIdentifiers;
import pe.axiz.core.commons.tx.TransactionRunner;
import pe.axiz.core.outbox.OutboxException;
import pe.axiz.core.outbox.OutboxRecord;
import pe.axiz.core.outbox.OutboxStore;
import pe.axiz.kit.jdbc.ConnectionProvider;

/** Transaction-aware JDBC outbox with persistent aggregate counters and fenced short claims. */
public final class JdbcOutboxStore implements OutboxStore {
  private static final String COLUMNS =
      "id,destination,aggregate_type,aggregate_id,event_type,envelope,status,attempts,"
          + "created_at,next_attempt_at,last_error,aggregate_seq,lease_owner,lease_until";
  private final ConnectionProvider connections;
  private final TransactionRunner transactions;
  private final String table;
  private final String counters;
  private final Dialect dialect;

  public JdbcOutboxStore(
      ConnectionProvider connections,
      TransactionRunner transactions,
      String table,
      Dialect dialect) {
    this.connections = Objects.requireNonNull(connections);
    this.transactions = Objects.requireNonNull(transactions);
    this.table = SqlIdentifiers.require(table);
    this.counters = SqlIdentifiers.require(table + "_aggregate");
    this.dialect = Objects.requireNonNull(dialect);
  }

  @Override
  public void append(OutboxRecord r) {
    transaction(
        c -> {
          append(c, r);
          return null;
        });
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification =
          "Private SQL path: identifiers pass SqlIdentifiers.require; values are bound. "
              + "Claim size is a positive integer and dialect fragments come from the closed Dialect enum.")
  private void append(Connection c, OutboxRecord r) throws SQLException {
    String upsert =
        dialect == Dialect.POSTGRESQL
            ? "INSERT INTO "
                + counters
                + " (aggregate_type,aggregate_id,last_seq) VALUES (?,?,1) ON CONFLICT"
                + " (aggregate_type,aggregate_id) DO UPDATE SET last_seq="
                + counters
                + ".last_seq+1 RETURNING last_seq"
            : "MERGE INTO "
                + counters
                + " c USING (VALUES (?,?)) v(aggregate_type,aggregate_id) ON"
                + " c.aggregate_type=v.aggregate_type AND c.aggregate_id=v.aggregate_id WHEN"
                + " MATCHED THEN UPDATE SET last_seq=c.last_seq+1 WHEN NOT MATCHED THEN"
                + " INSERT (aggregate_type,aggregate_id,last_seq) VALUES"
                + " (v.aggregate_type,v.aggregate_id,1)";
    long sequence;
    try (PreparedStatement ps = c.prepareStatement(upsert)) {
      ps.setString(1, r.aggregateType());
      ps.setString(2, r.aggregateId());
      if (dialect == Dialect.POSTGRESQL) {
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          sequence = rs.getLong(1);
        }
      } else {
        ps.executeUpdate();
        try (PreparedStatement next =
            c.prepareStatement(
                "SELECT last_seq FROM "
                    + counters
                    + " WHERE aggregate_type=? AND aggregate_id=?")) {
          next.setString(1, r.aggregateType());
          next.setString(2, r.aggregateId());
          try (ResultSet rs = next.executeQuery()) {
            rs.next();
            sequence = rs.getLong(1);
          }
        }
      }
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO "
                + table
                + """
                 (id,destination,aggregate_type,aggregate_id,event_type,envelope,status,attempts,
                 created_at,next_attempt_at,aggregate_seq) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """)) {
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
      ps.setLong(11, sequence);
      ps.executeUpdate();
    }
  }

  private String headPredicate() {
    return " NOT EXISTS (SELECT 1 FROM "
        + table
        + " p WHERE p.aggregate_type=h.aggregate_type AND p.aggregate_id=h.aggregate_id AND "
        + "p.aggregate_seq<h.aggregate_seq AND p.status NOT IN ('PUBLISHED','SKIPPED'))";
  }

  @Override
  public List<OutboxRecord> claimDue(int size, Duration lease, String owner) {
    if (size < 1 || !lease.isPositive() || owner.isBlank())
      throw new IllegalArgumentException("invalid claim");
    return transaction(
        c -> {
          return claimDue(c, size, lease, owner);
        });
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification =
          "Private SQL path: identifiers pass SqlIdentifiers.require; values are bound. "
              + "Claim size is a positive integer and dialect fragments come from the closed Dialect enum.")
  private List<OutboxRecord> claimDue(Connection c, int size, Duration lease, String owner)
      throws SQLException {
    String select =
        "SELECT "
            + COLUMNS
            + " FROM "
            + table
            + " h WHERE ((h.status='PENDING' AND h.next_attempt_at<="
            + dialect.nowExpression()
            + ") OR"
            + " (h.status='IN_FLIGHT' AND h.lease_until<="
            + dialect.nowExpression()
            + ")) AND "
            + headPredicate()
            + " ORDER BY h.created_at,h.id FETCH FIRST "
            + size
            + " ROWS ONLY"
            + dialect.lockClause();
    List<OutboxRecord> selected = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(select)) {
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) selected.add(record(rs));
      }
    }
    List<OutboxRecord> claimed = new ArrayList<>();
    for (OutboxRecord r : selected) {
      Instant leaseUntil;
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE "
                  + table
                  + " SET status='IN_FLIGHT',lease_owner=?,lease_until="
                  + dialect.leasedUntilExpression()
                  + " WHERE id=?"
                  + (dialect == Dialect.POSTGRESQL ? " RETURNING lease_until" : ""))) {
        ps.setString(1, owner);
        if (dialect == Dialect.POSTGRESQL) ps.setDouble(2, lease.toNanos() / 1_000_000_000.0);
        else ps.setLong(2, lease.toMillis());
        ps.setString(3, r.id());
        if (dialect == Dialect.POSTGRESQL) {
          try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new SQLException("Claimed outbox row disappeared");
            leaseUntil = rs.getTimestamp(1).toInstant();
          }
        } else {
          ps.executeUpdate();
          try (PreparedStatement read =
              c.prepareStatement("SELECT lease_until FROM " + table + " WHERE id=?")) {
            read.setString(1, r.id());
            try (ResultSet rs = read.executeQuery()) {
              if (!rs.next()) throw new SQLException("Claimed outbox row disappeared");
              leaseUntil = rs.getTimestamp(1).toInstant();
            }
          }
        }
      }
      claimed.add(
          new OutboxRecord(
              r.id(),
              r.destination(),
              r.aggregateType(),
              r.aggregateId(),
              r.eventType(),
              r.envelopeJson(),
              OutboxRecord.Status.IN_FLIGHT,
              r.attempts(),
              r.createdAt(),
              r.nextAttemptAt(),
              r.lastError(),
              r.aggregateSequence(),
              owner,
              leaseUntil));
    }
    return List.copyOf(claimed);
  }

  @Override
  public boolean markPublished(String id, String owner, Instant at) {
    return update(
            "UPDATE "
                + table
                + " SET status='PUBLISHED',published_at=?,last_error=NULL,lease_owner=NULL,"
                + "lease_until=NULL WHERE id=? AND lease_owner=? AND status='IN_FLIGHT'",
            Timestamp.from(at),
            id,
            owner)
        == 1;
  }

  @Override
  public boolean markFailed(
      String id, String owner, int attempts, Instant next, @Nullable String error, boolean giveUp) {
    return update(
            "UPDATE "
                + table
                + " SET status=?,attempts=?,next_attempt_at=?,last_error=?,lease_owner=NULL,"
                + "lease_until=NULL WHERE id=? AND lease_owner=? AND status='IN_FLIGHT'",
            giveUp ? "FAILED" : "PENDING",
            attempts,
            Timestamp.from(next),
            trim(error),
            id,
            owner)
        == 1;
  }

  @Override
  public boolean retry(String id) {
    return update(
            "UPDATE "
                + table
                + " SET status='PENDING',attempts=0,lease_owner=NULL,lease_until=NULL WHERE id=?"
                + " AND status='FAILED'",
            id)
        == 1;
  }

  @Override
  public boolean skip(String id, String reason) {
    if (reason.isBlank() || reason.length() > 1000)
      throw new IllegalArgumentException("skip reason required, maximum 1000 characters");
    return update("UPDATE " + table + " SET status='SKIPPED' WHERE id=? AND status='FAILED'", id)
        == 1;
  }

  @Override
  public long countBlockedAggregates() {
    return count(
        "SELECT COUNT(*) FROM (SELECT aggregate_type,aggregate_id FROM "
            + table
            + " WHERE status='FAILED' GROUP BY aggregate_type,aggregate_id) blocked");
  }

  @Override
  public int purgePublished(Instant before) {
    return update(
        "DELETE FROM " + table + " WHERE status='PUBLISHED' AND published_at<?",
        Timestamp.from(before));
  }

  @Override
  public long countSkipped() {
    return count("SELECT COUNT(*) FROM " + table + " WHERE status='SKIPPED'");
  }

  @Override
  public long countPending() {
    return count("SELECT COUNT(*) FROM " + table + " WHERE status IN ('PENDING','IN_FLIGHT')");
  }

  @Override
  public void verifySchema() {
    transaction(
        c -> {
          verifySchema(c);
          return null;
        });
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification =
          "Private SQL path: identifiers pass SqlIdentifiers.require; values are bound. "
              + "Claim size is a positive integer and dialect fragments come from the closed Dialect enum.")
  private void verifySchema(Connection c) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("SELECT " + COLUMNS + " FROM " + table + " WHERE 1=0")) {
      ps.executeQuery().close();
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT aggregate_type,aggregate_id,last_seq FROM " + counters + " WHERE 1=0")) {
      ps.executeQuery().close();
    }
  }

  private int update(String statement, @Nullable Object... values) {
    return transaction(
        c -> {
          return update(c, statement, values);
        });
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification =
          "Private SQL path: identifiers pass SqlIdentifiers.require; values are bound. "
              + "Claim size is a positive integer and dialect fragments come from the closed Dialect enum.")
  private int update(Connection c, String statement, @Nullable Object... values)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(statement)) {
      for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
      return ps.executeUpdate();
    }
  }

  private long count(String statement) {
    return transaction(
        c -> {
          return count(c, statement);
        });
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification =
          "Private SQL path: identifiers pass SqlIdentifiers.require; values are bound. "
              + "Claim size is a positive integer and dialect fragments come from the closed Dialect enum.")
  private long count(Connection c, String statement) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(statement);
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static @Nullable String trim(@Nullable String value) {
    return value == null ? null : value.substring(0, Math.min(1000, value.length()));
  }

  private static OutboxRecord record(ResultSet rs) throws SQLException {
    Timestamp lease = rs.getTimestamp(14);
    return new OutboxRecord(
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
        rs.getString(11),
        rs.getLong(12),
        rs.getString(13),
        lease == null ? null : lease.toInstant());
  }

  private <T extends @Nullable Object> T transaction(ConnectionWork<T> work) {
    try {
      return transactions.inTransaction(
          () -> {
            Connection c = connections.get();
            try (ConnectionRelease ignored = () -> connections.release(c)) {
              if (c.getAutoCommit()) {
                throw new SQLException("Outbox requires a transaction-bound connection", "25000");
              }
              return work.run(c);
            }
          });
    } catch (SQLException failure) {
      throw new OutboxException("Outbox SQL failed: " + failure.getSQLState(), failure);
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new OutboxException("Outbox transaction failed", failure);
    }
  }

  @FunctionalInterface
  private interface ConnectionRelease extends AutoCloseable {
    @Override
    void close() throws SQLException;
  }

  @FunctionalInterface
  private interface ConnectionWork<T extends @Nullable Object> {
    T run(Connection connection) throws SQLException;
  }
}
