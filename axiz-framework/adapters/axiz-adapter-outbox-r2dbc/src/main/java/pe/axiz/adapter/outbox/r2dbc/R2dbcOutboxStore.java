package pe.axiz.adapter.outbox.r2dbc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.outbox.OutboxException;
import pe.axiz.core.outbox.OutboxRecord;
import pe.axiz.core.outbox.OutboxStore;
import pe.axiz.kit.r2dbc.R2dbcSql;
import pe.axiz.kit.r2dbc.ReactiveSql;
import pe.axiz.kit.r2dbc.SqlParams;
import pe.axiz.kit.r2dbc.SqlRow;
import pe.axiz.kit.sql.SqlIdentifiers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Almacén de outbox sobre Aurora PostgreSQL mediante R2DBC, con la misma tabla que el almacén JDBC
 * ({@code axiz/sql/outbox-postgresql.sql}). {@link #insert} no bloquea y se une a la transacción
 * reactiva de quien llama; los métodos de {@link OutboxStore} son para el relay, que corre en su
 * propio hilo dentro de una transacción de {@code R2dbcTransactionRunner}, y esperan hasta {@code
 * timeout}.
 */
public final class R2dbcOutboxStore implements OutboxStore {

  private static final String COLUMNS =
      "id, destination, aggregate_type, aggregate_id, event_type, envelope, status, "
          + "attempts, created_at, next_attempt_at, last_error, aggregate_seq, lease_owner, "
          + "lease_until";

  private final ReactiveSql sql;
  private final String table;
  private final Duration timeout;

  @Override
  public @Nullable Object transactionResource() {
    return sql.transactionResource();
  }

  public R2dbcOutboxStore(ReactiveSql sql, String table, Duration timeout) {
    this.sql = Objects.requireNonNull(sql);
    this.table = SqlIdentifiers.require(table);
    this.timeout = Objects.requireNonNull(timeout);
  }

  /** Inserta un registro en la transacción reactiva actual. */
  public Mono<Void> insert(OutboxRecord r) {
    return sql.queryOne(
            "INSERT INTO "
                + table
                + "_aggregate (aggregate_type,aggregate_id,last_seq) VALUES"
                + " (:aggregateType,:aggregateId,1) ON CONFLICT (aggregate_type,aggregate_id) DO"
                + " UPDATE SET last_seq="
                + table
                + "_aggregate.last_seq+1 RETURNING last_seq",
            SqlParams.of()
                .bind("aggregateType", r.aggregateType(), String.class)
                .bind("aggregateId", r.aggregateId(), String.class),
            row -> row.require("last_seq", Long.class))
        .switchIfEmpty(Mono.error(new OutboxException("Outbox aggregate counter returned nothing")))
        .flatMap(
            sequence ->
                sql.update(
                    "INSERT INTO "
                        + table
                        + " (id, destination, aggregate_type, aggregate_id, event_type, envelope,"
                        + " status, attempts, created_at, next_attempt_at, aggregate_seq) VALUES"
                        + " (:id, :destination, :aggregateType, :aggregateId, :eventType,"
                        + " :envelope, :status, :attempts, :createdAt, :nextAttemptAt, :sequence)",
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
                        .bind("nextAttemptAt", r.nextAttemptAt())
                        .bind("sequence", sequence)))
        .then();
  }

  @Override
  public void append(OutboxRecord record) {
    run(insert(record));
  }

  private String heads() {
    return " NOT EXISTS (SELECT 1 FROM "
        + table
        + " p WHERE p.aggregate_type=h.aggregate_type AND p.aggregate_id=h.aggregate_id AND "
        + "p.aggregate_seq<h.aggregate_seq AND p.status NOT IN ('PUBLISHED','SKIPPED'))";
  }

  public Mono<List<OutboxRecord>> claim(int size, Duration lease, String owner) {
    if (size < 1 || !lease.isPositive() || owner.isBlank())
      return Mono.error(new IllegalArgumentException("invalid claim"));
    return sql.query(
            "SELECT "
                + COLUMNS
                + " FROM "
                + table
                + " h WHERE ((h.status='PENDING' AND h.next_attempt_at<=statement_timestamp()) OR"
                + " (h.status='IN_FLIGHT' AND h.lease_until<=statement_timestamp())) AND "
                + heads()
                + " ORDER BY h.created_at,h.id FETCH FIRST "
                + size
                + " ROWS ONLY FOR UPDATE SKIP LOCKED",
            SqlParams.of(),
            R2dbcOutboxStore::record)
        .collectList()
        .flatMapMany(Flux::fromIterable)
        .concatMap(
            r ->
                sql.query(
                        "UPDATE "
                            + table
                            + " SET status='IN_FLIGHT',lease_owner=:owner,lease_until=statement_timestamp()"
                            + " + make_interval(secs => :seconds) WHERE id=:id RETURNING"
                            + " lease_until",
                        SqlParams.of()
                            .bind("owner", owner)
                            .bind("seconds", lease.toNanos() / 1_000_000_000.0)
                            .bind("id", r.id()),
                        row -> row.require("lease_until", Instant.class))
                    .single()
                    .map(
                        until ->
                            OutboxRecord.builder(
                                    r.id(),
                                    r.destination(),
                                    r.eventType(),
                                    r.envelopeJson(),
                                    r.createdAt())
                                .aggregate(r.aggregateType(), r.aggregateId())
                                .status(OutboxRecord.Status.IN_FLIGHT)
                                .attempts(r.attempts())
                                .nextAttemptAt(r.nextAttemptAt())
                                .lastError(r.lastError())
                                .aggregateSequence(r.aggregateSequence())
                                .lease(owner, until)
                                .build()))
        .collectList();
  }

  @Override
  public List<OutboxRecord> claimDue(int size, Duration lease, String owner) {
    return await(claim(size, lease, owner));
  }

  @Override
  public boolean markPublished(String id, String owner, Instant at) {
    return await(
            sql.update(
                "UPDATE "
                    + table
                    + " SET status='PUBLISHED',published_at=:at,last_error=NULL,lease_owner=NULL,"
                    + "lease_until=NULL WHERE id=:id AND lease_owner=:owner AND status='IN_FLIGHT'",
                SqlParams.of().bind("id", id).bind("owner", owner).bind("at", at)))
        == 1;
  }

  @Override
  public boolean markFailed(
      String id, String owner, int attempts, Instant next, @Nullable String error, boolean giveUp) {
    return await(
            sql.update(
                "UPDATE "
                    + table
                    + """
                     SET status=:status,attempts=:attempts,next_attempt_at=:next,last_error=:error,
                     lease_owner=NULL,lease_until=NULL
                     WHERE id=:id AND lease_owner=:owner AND status='IN_FLIGHT'
                    """,
                SqlParams.of()
                    .bind("id", id)
                    .bind("owner", owner)
                    .bind("status", giveUp ? "FAILED" : "PENDING")
                    .bind("attempts", attempts)
                    .bind("next", next)
                    .bind(
                        "error",
                        error == null ? null : error.substring(0, Math.min(error.length(), 1000)),
                        String.class)))
        == 1;
  }

  @Override
  public boolean retry(String id) {
    return await(
            sql.update(
                "UPDATE "
                    + table
                    + " SET status='PENDING',attempts=0,lease_owner=NULL,lease_until=NULL WHERE"
                    + " id=:id AND status='FAILED'",
                SqlParams.of().bind("id", id)))
        == 1;
  }

  @Override
  public boolean skip(String id, String reason) {
    if (reason.isBlank() || reason.length() > 1000)
      throw new IllegalArgumentException("skip reason required, maximum 1000 characters");
    return await(
            sql.update(
                "UPDATE " + table + " SET status='SKIPPED' WHERE id=:id AND status='FAILED'",
                SqlParams.of().bind("id", id)))
        == 1;
  }

  @Override
  public long countBlockedAggregates() {
    return await(
        sql.queryOne(
                "SELECT COUNT(*) AS blocked FROM (SELECT aggregate_type,aggregate_id FROM "
                    + table
                    + " WHERE status='FAILED' GROUP BY aggregate_type,aggregate_id) b",
                SqlParams.of(),
                row -> row.require("blocked", Long.class))
            .defaultIfEmpty(0L));
  }

  @Override
  public void verifySchema() {
    run(
        sql.query(
                "SELECT " + COLUMNS + " FROM " + table + " WHERE 1=0",
                SqlParams.of(),
                R2dbcOutboxStore::record)
            .then()
            .then(
                sql.query(
                        "SELECT last_seq FROM " + table + "_aggregate WHERE 1=0",
                        SqlParams.of(),
                        r -> r.require("last_seq", Long.class))
                    .then()));
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
  public long countSkipped() {
    return await(
        sql.queryOne(
                "SELECT COUNT(*) AS skipped FROM " + table + " WHERE status='SKIPPED'",
                SqlParams.of(),
                r -> r.require("skipped", Long.class))
            .defaultIfEmpty(0L));
  }

  @Override
  public long countPending() {
    Long n =
        await(
            sql.queryOne(
                    "SELECT COUNT(*) AS pending FROM "
                        + table
                        + " WHERE status IN ('PENDING','IN_FLIGHT')",
                    SqlParams.of(),
                    r -> r.require("pending", Long.class))
                .defaultIfEmpty(0L));
    return n;
  }

  static OutboxRecord record(SqlRow r) {
    Integer attempts = r.get("attempts", Integer.class);
    return OutboxRecord.builder(
            r.require("id", String.class),
            r.require("destination", String.class),
            r.require("event_type", String.class),
            r.require("envelope", String.class),
            r.require("created_at", Instant.class))
        .aggregate(r.get("aggregate_type", String.class), r.get("aggregate_id", String.class))
        .status(OutboxRecord.Status.valueOf(r.require("status", String.class)))
        .attempts(attempts == null ? 0 : attempts)
        .nextAttemptAt(r.require("next_attempt_at", Instant.class))
        .lastError(r.get("last_error", String.class))
        .aggregateSequence(
            r.get("aggregate_seq", Long.class) == null ? 0 : r.require("aggregate_seq", Long.class))
        .lease(r.get("lease_owner", String.class), r.get("lease_until", Instant.class))
        .build();
  }

  /** Espera una sentencia que produce un valor (número de filas, filas). */
  private <T> T await(Mono<T> work) {
    T value = block(work);
    if (value == null) {
      throw new OutboxException("Outbox R2DBC statement returned nothing");
    }
    return value;
  }

  /** Espera una sentencia que termina sin valor ({@code Mono<Void>}). */
  private void run(Mono<Void> work) {
    block(work);
  }

  private <T> @Nullable T block(Mono<T> work) {
    try {
      return (sql instanceof R2dbcSql direct ? direct.withCallingTransaction(work) : work)
          .block(timeout);
    } catch (RuntimeException e) {
      throw new OutboxException("Outbox R2DBC statement failed: " + e.getMessage(), e);
    }
  }
}
