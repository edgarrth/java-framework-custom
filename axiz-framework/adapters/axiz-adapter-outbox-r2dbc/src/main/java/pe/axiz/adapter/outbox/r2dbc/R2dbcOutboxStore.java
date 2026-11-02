package pe.axiz.adapter.outbox.r2dbc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.jdbc.SqlStates;
import pe.axiz.core.outbox.OutboxException;
import pe.axiz.core.outbox.OutboxRecord;
import pe.axiz.core.outbox.OutboxStore;
import pe.axiz.kit.r2dbc.ReactiveSql;
import pe.axiz.kit.r2dbc.SqlParams;
import pe.axiz.kit.r2dbc.SqlRow;
import reactor.core.publisher.Mono;

/**
 * Outbox store on Aurora PostgreSQL over R2DBC, same table as the JDBC store ({@code
 * axiz/sql/outbox-postgresql.sql}). {@link #insert} is non-blocking and joins the caller's reactive
 * transaction; the {@link OutboxStore} methods are for the relay, which runs on its own thread
 * inside an {@code R2dbcTransactionRunner} transaction, and wait up to {@code timeout}.
 */
public final class R2dbcOutboxStore implements OutboxStore {

  private static final int MAX_ERROR = 1000;
  private static final String COLUMNS =
      "id, destination, aggregate_type, aggregate_id, event_type, envelope, status, attempts,"
          + " created_at, next_attempt_at, last_error";

  private final ReactiveSql sql;
  private final String table;
  private final Duration timeout;

  public R2dbcOutboxStore(ReactiveSql sql, String table, Duration timeout) {
    this.sql = Objects.requireNonNull(sql);
    this.table = SqlStates.identifier(table);
    this.timeout = Objects.requireNonNull(timeout);
  }

  /** Inserts a record in the current reactive transaction. */
  public Mono<Void> insert(OutboxRecord r) {
    return sql.update(
            "INSERT INTO "
                + table
                + " (id, destination, aggregate_type, aggregate_id, event_type, envelope, status,"
                + " attempts, created_at, next_attempt_at) VALUES (:id, :destination,"
                + " :aggregateType, :aggregateId, :eventType, :envelope, :status, :attempts,"
                + " :createdAt, :nextAttemptAt)",
            SqlParams.of()
                .bind("id", r.id())
                .bind("destination", r.destination())
                .bind("aggregateType", r.aggregateType(), String.class)
                .bind("aggregateId", r.aggregateId(), String.class)
                .bind("eventType", r.eventType())
                .bind("envelope", r.envelopeJson())
                .bind("status", r.status().name())
                .bind("attempts", r.attempts())
                .bind("createdAt", r.createdAt())
                .bind("nextAttemptAt", r.nextAttemptAt()))
        .then();
  }

  @Override
  public void append(OutboxRecord record) {
    run(insert(record));
  }

  @Override
  public List<OutboxRecord> lockDue(int size, Instant now) {
    return await(
        sql.query(
                "SELECT "
                    + COLUMNS
                    + " FROM "
                    + table
                    + " WHERE status = 'PENDING' AND next_attempt_at <= :now ORDER BY created_at,"
                    + " id FETCH FIRST "
                    + Math.max(1, size)
                    + " ROWS ONLY FOR UPDATE SKIP LOCKED",
                SqlParams.of().bind("now", now),
                R2dbcOutboxStore::record)
            .collectList());
  }

  @Override
  public void markPublished(String id, Instant at) {
    await(
        sql.update(
            "UPDATE "
                + table
                + " SET status = 'PUBLISHED', published_at = :at, last_error = NULL WHERE id = :id",
            SqlParams.of().bind("at", at).bind("id", id)));
  }

  @Override
  public void markFailed(
      String id, int attempts, Instant nextAttemptAt, @Nullable String error, boolean giveUp) {
    String status = giveUp ? OutboxRecord.Status.FAILED.name() : OutboxRecord.Status.PENDING.name();
    String trimmed =
        error == null ? null : error.length() > MAX_ERROR ? error.substring(0, MAX_ERROR) : error;
    await(
        sql.update(
            "UPDATE "
                + table
                + " SET status = :status, attempts = :attempts, next_attempt_at = :next,"
                + " last_error = :error WHERE id = :id",
            SqlParams.of()
                .bind("status", status)
                .bind("attempts", attempts)
                .bind("next", nextAttemptAt)
                .bind("error", trimmed, String.class)
                .bind("id", id)));
  }

  @Override
  public int purgePublished(Instant before) {
    long n =
        await(
            sql.update(
                "DELETE FROM " + table + " WHERE status = 'PUBLISHED' AND published_at < :before",
                SqlParams.of().bind("before", before)));
    return (int) n;
  }

  @Override
  public long countPending() {
    Long n =
        await(
            sql.queryOne(
                    "SELECT COUNT(*) AS pending FROM " + table + " WHERE status = 'PENDING'",
                    SqlParams.of(),
                    r -> r.require("pending", Long.class))
                .defaultIfEmpty(0L));
    return n;
  }

  static OutboxRecord record(SqlRow r) {
    Integer attempts = r.get("attempts", Integer.class);
    return new OutboxRecord(
        r.require("id", String.class),
        r.require("destination", String.class),
        r.get("aggregate_type", String.class),
        r.get("aggregate_id", String.class),
        r.require("event_type", String.class),
        r.require("envelope", String.class),
        OutboxRecord.Status.valueOf(r.require("status", String.class)),
        attempts == null ? 0 : attempts,
        r.require("created_at", Instant.class),
        r.require("next_attempt_at", Instant.class),
        r.get("last_error", String.class));
  }

  /** Waits for a statement that produces a value (row count, rows). */
  private <T> T await(Mono<T> work) {
    T value = block(work);
    if (value == null) {
      throw new OutboxException("Outbox R2DBC statement returned nothing");
    }
    return value;
  }

  /** Waits for a statement that completes without a value ({@code Mono<Void>}). */
  private void run(Mono<Void> work) {
    block(work);
  }

  private <T> @Nullable T block(Mono<T> work) {
    try {
      return work.block(timeout);
    } catch (RuntimeException e) {
      throw new OutboxException("Outbox R2DBC statement failed: " + e.getMessage(), e);
    }
  }
}
