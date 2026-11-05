package pe.axiz.kit.r2dbc;

import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Non-blocking SQL with named parameters ({@code :name}). Implementations take part in the caller's
 * transaction: the Spring starter uses the transaction-aware {@code DatabaseClient}; {@link
 * R2dbcSql} uses the connection bound by {@link R2dbcSql#inTransaction} or by {@link
 * R2dbcTransactionRunner}.
 */
public interface ReactiveSql {

  /** Identity of the transaction-aware connection factory; unknown integrations return null. */
  default @Nullable Object transactionResource() {
    return null;
  }

  /** Runs an INSERT, UPDATE or DELETE and emits the number of affected rows. */
  Mono<Long> update(String sql, SqlParams params);

  /** Runs a query and emits the mapped rows. */
  <T> Flux<T> query(String sql, SqlParams params, RowMapper<T> mapper);

  /** The first row of a query, or empty. */
  default <T> Mono<T> queryOne(String sql, SqlParams params, RowMapper<T> mapper) {
    return query(sql, params, mapper).next();
  }
}
