package pe.axiz.core.inbox.r2dbc;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import pe.axiz.core.commons.jdbc.SqlStates;
import pe.axiz.core.inbox.InboxException;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.core.persistence.r2dbc.ReactiveSql;
import pe.axiz.core.persistence.r2dbc.SqlParams;
import reactor.core.publisher.Mono;

/**
 * Inbox on Aurora PostgreSQL over R2DBC, same table as the JDBC store ({@code
 * axiz/sql/inbox-postgresql.sql}). The dispatcher calls it from the consumer threads (Kafka
 * listener, SQS poller), never from an event loop, so the calls wait up to {@code timeout}.
 */
public final class R2dbcInboxStore implements InboxStore {

  private final ReactiveSql sql;
  private final String table;
  private final Duration timeout;

  public R2dbcInboxStore(ReactiveSql sql, String table, Duration timeout) {
    this.sql = Objects.requireNonNull(sql);
    this.table = SqlStates.identifier(table);
    this.timeout = Objects.requireNonNull(timeout);
  }

  @Override
  public Acquisition tryAcquire(
      String consumer,
      String messageId,
      String owner,
      Instant now,
      Instant leaseUntil,
      Instant expiresAt) {
    long inserted =
        await(
            sql.update(
                "INSERT INTO "
                    + table
                    + " (consumer, message_id, status, owner, received_at, lease_until,"
                    + " expires_at) VALUES (:consumer, :messageId, 'IN_PROGRESS', :owner, :now,"
                    + " :leaseUntil, :expiresAt) ON CONFLICT (consumer, message_id) DO NOTHING",
                SqlParams.of()
                    .bind("consumer", consumer)
                    .bind("messageId", messageId)
                    .bind("owner", owner)
                    .bind("now", now)
                    .bind("leaseUntil", leaseUntil)
                    .bind("expiresAt", expiresAt)));
    if (inserted == 1) {
      return Acquisition.acquired();
    }
    // take over an expired in-progress registration (its delivery died)
    long taken =
        await(
            sql.update(
                "UPDATE "
                    + table
                    + " SET owner = :owner, lease_until = :leaseUntil, received_at = :now WHERE"
                    + " consumer = :consumer AND message_id = :messageId AND status ="
                    + " 'IN_PROGRESS' AND lease_until < :now",
                SqlParams.of()
                    .bind("owner", owner)
                    .bind("leaseUntil", leaseUntil)
                    .bind("now", now)
                    .bind("consumer", consumer)
                    .bind("messageId", messageId)));
    if (taken == 1) {
      return Acquisition.acquired();
    }
    Acquisition current =
        sql.queryOne(
                "SELECT status, lease_until FROM "
                    + table
                    + " WHERE consumer = :consumer AND message_id = :messageId",
                SqlParams.of().bind("consumer", consumer).bind("messageId", messageId),
                row ->
                    "PROCESSED".equals(row.get("status", String.class))
                        ? Acquisition.processed()
                        : Acquisition.busy(row.get("lease_until", Instant.class)))
            .defaultIfEmpty(Acquisition.busy(now)) // released meanwhile: redeliver right away
            .onErrorMap(e -> new InboxException("Inbox R2DBC statement failed: " + e, e))
            .block(timeout);
    return current == null ? Acquisition.busy(now) : current;
  }

  @Override
  public boolean markProcessed(String consumer, String messageId, String owner, Instant at) {
    return await(
            sql.update(
                "UPDATE "
                    + table
                    + " SET status = 'PROCESSED', processed_at = :at WHERE consumer = :consumer"
                    + " AND message_id = :messageId AND owner = :owner AND status ="
                    + " 'IN_PROGRESS'",
                SqlParams.of()
                    .bind("at", at)
                    .bind("consumer", consumer)
                    .bind("messageId", messageId)
                    .bind("owner", owner)))
        == 1;
  }

  @Override
  public boolean release(String consumer, String messageId, String owner) {
    return await(
            sql.update(
                "DELETE FROM "
                    + table
                    + " WHERE consumer = :consumer AND message_id = :messageId AND owner = :owner"
                    + " AND status = 'IN_PROGRESS'",
                SqlParams.of()
                    .bind("consumer", consumer)
                    .bind("messageId", messageId)
                    .bind("owner", owner)))
        == 1;
  }

  @Override
  public int purgeExpired(Instant now) {
    return (int)
        await(
            sql.update(
                "DELETE FROM " + table + " WHERE expires_at < :now",
                SqlParams.of().bind("now", now)));
  }

  @Override
  public void verifySchema() {
    try {
      sql.query(
              "SELECT consumer, message_id, status, owner, lease_until, expires_at FROM "
                  + table
                  + " WHERE 1 = 0",
              SqlParams.of(),
              r -> 1)
          .collectList()
          .block(timeout);
    } catch (RuntimeException e) {
      throw new InboxException(
          "Inbox table "
              + table
              + " is missing or outdated (column owner since axiz 0.1): add a migration with"
              + " axiz/sql/inbox-postgresql-upgrade-owner.sql, never edit an applied one",
          e);
    }
  }

  private long await(Mono<Long> work) {
    try {
      Long n = work.block(timeout);
      return n == null ? 0 : n;
    } catch (RuntimeException e) {
      throw new InboxException("Inbox R2DBC statement failed: " + e.getMessage(), e);
    }
  }
}
