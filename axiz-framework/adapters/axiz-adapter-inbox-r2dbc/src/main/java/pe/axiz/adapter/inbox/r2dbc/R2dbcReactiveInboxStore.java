package pe.axiz.adapter.inbox.r2dbc;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.inbox.InboxException;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.core.inbox.InboxStore.Acquisition;
import pe.axiz.kit.r2dbc.ReactiveSql;
import pe.axiz.kit.r2dbc.SqlParams;
import pe.axiz.kit.sql.SqlIdentifiers;
import reactor.core.publisher.Mono;

/**
 * Inbox PostgreSQL no bloqueante con actualizaciones atómicas verificadas por propietario y E/S
 * R2DBC cancelable.
 */
public final class R2dbcReactiveInboxStore implements ReactiveInboxStore {
  private final ReactiveSql sql;
  private final String table;

  public R2dbcReactiveInboxStore(ReactiveSql sql, String table) {
    this.sql = Objects.requireNonNull(sql);
    this.table = SqlIdentifiers.require(table);
  }

  @Override
  public @Nullable Object transactionResource() {
    return sql.transactionResource();
  }

  @Override
  public Mono<Acquisition> tryAcquire(
      String consumer,
      String messageId,
      String owner,
      Instant now,
      Instant leaseUntil,
      Instant expiresAt) {
    return Mono.defer(
        () -> {
          InboxStore.validateWindow(now, leaseUntil, expiresAt);
          return acquire(consumer, messageId, owner, now, leaseUntil, expiresAt);
        });
  }

  private Mono<Acquisition> acquire(
      String consumer,
      String messageId,
      String owner,
      Instant now,
      Instant leaseUntil,
      Instant expiresAt) {
    return statement(
        () ->
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
                        .bind("expiresAt", expiresAt))
                .flatMap(
                    inserted -> {
                      if (inserted == 1) return Mono.just(Acquisition.acquired());
                      return sql.update(
                              "UPDATE "
                                  + table
                                  + " SET owner = :owner, lease_until = :leaseUntil, received_at "
                                  + "= :now, expires_at = :expiresAt WHERE consumer = "
                                  + ":consumer AND message_id = :messageId AND status = "
                                  + "'IN_PROGRESS' AND lease_until < "
                                  + ":now",
                              SqlParams.of()
                                  .bind("owner", owner)
                                  .bind("leaseUntil", leaseUntil)
                                  .bind("expiresAt", expiresAt)
                                  .bind("now", now)
                                  .bind("consumer", consumer)
                                  .bind("messageId", messageId))
                          .flatMap(
                              taken ->
                                  taken == 1
                                      ? Mono.just(Acquisition.acquired())
                                      : sql.queryOne(
                                              "SELECT status, lease_until FROM "
                                                  + table
                                                  + " WHERE consumer = :consumer AND message_id ="
                                                  + " :messageId",
                                              SqlParams.of()
                                                  .bind("consumer", consumer)
                                                  .bind("messageId", messageId),
                                              row ->
                                                  "PROCESSED"
                                                          .equals(row.get("status", String.class))
                                                      ? Acquisition.processed()
                                                      : Acquisition.busy(
                                                          row.get("lease_until", Instant.class)))
                                          .defaultIfEmpty(Acquisition.busy(now)));
                    }));
  }

  @Override
  public Mono<Boolean> markProcessed(String consumer, String messageId, String owner, Instant at) {
    return statement(
        () ->
            sql.update(
                    "UPDATE "
                        + table
                        + " SET status = 'PROCESSED', processed_at = :at WHERE consumer = :consumer"
                        + " AND message_id = :messageId AND owner = :owner AND status ="
                        + " 'IN_PROGRESS' AND lease_until > :at AND expires_at > :at",
                    SqlParams.of()
                        .bind("at", at)
                        .bind("consumer", consumer)
                        .bind("messageId", messageId)
                        .bind("owner", owner))
                .map(count -> count == 1));
  }

  @Override
  public Mono<Boolean> release(String consumer, String messageId, String owner) {
    return statement(
        () ->
            sql.update(
                    "DELETE FROM "
                        + table
                        + " WHERE consumer = :consumer AND message_id = :messageId AND owner ="
                        + " :owner AND status = 'IN_PROGRESS'",
                    SqlParams.of()
                        .bind("consumer", consumer)
                        .bind("messageId", messageId)
                        .bind("owner", owner))
                .map(count -> count == 1));
  }

  @Override
  public Mono<Integer> purgeExpired(Instant now) {
    return statement(
        () ->
            sql.update(
                    "DELETE FROM " + table + " WHERE expires_at < :now AND status <> 'IN_PROGRESS'",
                    SqlParams.of().bind("now", now))
                .map(Math::toIntExact));
  }

  @Override
  public Mono<Void> verifySchema() {
    return Mono.defer(
            () ->
                sql.query(
                        "SELECT consumer, message_id, status, owner, lease_until, expires_at FROM "
                            + table
                            + " WHERE 1 = 0",
                        SqlParams.of(),
                        row -> 1)
                    .then())
        .onErrorMap(
            error ->
                new InboxException(
                    "Inbox table "
                        + table
                        + " does not match the current v2 schema (owner required): see"
                        + " axiz/sql/inbox-postgresql.sql and manage schema with reviewed"
                        + " migrations",
                    error));
  }

  private static <T> Mono<T> statement(Supplier<Mono<T>> work) {
    return Mono.defer(work)
        .onErrorMap(
            error ->
                error instanceof InboxException
                    ? error
                    : new InboxException(
                        "Inbox R2DBC statement failed: " + error.getMessage(), error));
  }
}
