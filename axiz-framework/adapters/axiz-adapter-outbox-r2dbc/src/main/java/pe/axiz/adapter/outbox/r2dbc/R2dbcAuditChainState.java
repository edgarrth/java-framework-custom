package pe.axiz.adapter.outbox.r2dbc;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.IsolationLevel;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.UnaryOperator;
import pe.axiz.core.audit.AuditIntegrity;
import pe.axiz.core.audit.ChainCheckpoint;
import pe.axiz.kit.r2dbc.ReactiveSql;
import pe.axiz.kit.r2dbc.SqlParams;
import pe.axiz.kit.reactor.audit.ReactiveAuditChainState;
import pe.axiz.kit.sql.SqlIdentifiers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** PostgreSQL audit heads in the caller's native transaction, without an imperative bridge. */
public final class R2dbcAuditChainState implements ReactiveAuditChainState {
  private final ReactiveSql sql;
  private final ConnectionFactory connections;
  private final String table;
  private final Clock clock;
  private final Duration timeout;

  public R2dbcAuditChainState(
      ReactiveSql sql, ConnectionFactory connections, String table, Clock clock, Duration timeout) {
    this.sql = Objects.requireNonNull(sql);
    this.connections = Objects.requireNonNull(connections);
    if (sql.transactionResource() != connections)
      throw new IllegalArgumentException(
          "Audit chain SQL must use the configured transaction resource");
    this.table = SqlIdentifiers.require(table);
    this.clock = Objects.requireNonNull(clock);
    if (!timeout.isPositive() || timeout.compareTo(Duration.ofMinutes(1)) > 0)
      throw new IllegalArgumentException(
          "Audit statement timeout must be positive and at most one minute");
    this.timeout = timeout;
  }

  @Override
  public Object transactionResource() {
    return connections;
  }

  @Override
  public Mono<AuditIntegrity> advance(String chainId, UnaryOperator<AuditIntegrity> step) {
    return Mono.defer(
            () -> {
              AuditIntegrity initial = AuditIntegrity.initial(chainId);
              return sql.isTransactionActive()
                  .flatMap(
                      active -> {
                        if (!active)
                          return Mono.error(
                              new IllegalStateException(
                                  "Audit chain advance requires an active transaction"));
                        return sql.update(
                                "INSERT INTO "
                                    + table
                                    + " (chain_id,last_sequence,last_hash,updated_at) VALUES"
                                    + " (:chain,0,:hash,:at) ON CONFLICT (chain_id) DO NOTHING",
                                SqlParams.of()
                                    .bind("chain", chainId)
                                    .bind("hash", initial.hash())
                                    .bind("at", clock.instant()))
                            .then(
                                sql.queryOne(
                                    "SELECT last_sequence,last_hash FROM "
                                        + table
                                        + " WHERE chain_id=:chain FOR UPDATE",
                                    SqlParams.of().bind("chain", chainId),
                                    row ->
                                        AuditIntegrity.head(
                                            chainId,
                                            row.require("last_sequence", Long.class),
                                            row.require("last_hash", String.class))))
                            .switchIfEmpty(
                                Mono.error(
                                    new IllegalStateException("Audit chain head is missing")))
                            .flatMap(
                                head -> {
                                  AuditIntegrity next =
                                      Objects.requireNonNull(step.apply(head), "chain successor");
                                  head.requireSuccessor(next);
                                  return sql.update(
                                          "UPDATE "
                                              + table
                                              + " SET last_sequence=:sequence,last_hash=:hash,updated_at=:at"
                                              + " WHERE chain_id=:chain",
                                          SqlParams.of()
                                              .bind("sequence", next.sequence())
                                              .bind("hash", next.hash())
                                              .bind("at", clock.instant())
                                              .bind("chain", chainId))
                                      .flatMap(
                                          changed ->
                                              changed == 1
                                                  ? Mono.just(next)
                                                  : Mono.error(
                                                      new IllegalStateException(
                                                          "Audit chain head update failed")));
                                });
                      });
            })
        .timeout(timeout);
  }

  @Override
  public Mono<ChainCheckpoint> checkpoint(String chainId) {
    return Mono.defer(
            () -> {
              AuditIntegrity initial = AuditIntegrity.initial(chainId);
              return Mono.usingWhen(
                  Mono.from(connections.create()),
                  connection -> {
                    if (!connection.isAutoCommit())
                      return Mono.error(
                          new IllegalStateException(
                              "Audit checkpoint requires an independent autocommit connection"));
                    return Mono.from(
                            connection.setTransactionIsolationLevel(IsolationLevel.READ_COMMITTED))
                        .thenMany(
                            Flux.from(
                                connection
                                    .createStatement(
                                        "SELECT last_sequence,last_hash FROM "
                                            + table
                                            + " WHERE chain_id=$1")
                                    .bind(0, chainId)
                                    .execute()))
                        .concatMap(
                            result ->
                                result.map(
                                    (row, metadata) ->
                                        new ChainCheckpoint(
                                            chainId,
                                            Objects.requireNonNull(
                                                row.get("last_sequence", Long.class)),
                                            Objects.requireNonNull(
                                                row.get("last_hash", String.class)),
                                            clock.instant())))
                        .singleOrEmpty()
                        .switchIfEmpty(
                            Mono.fromSupplier(
                                () ->
                                    new ChainCheckpoint(
                                        chainId, 0, initial.hash(), clock.instant())));
                  },
                  Connection::close);
            })
        .timeout(timeout);
  }
}
