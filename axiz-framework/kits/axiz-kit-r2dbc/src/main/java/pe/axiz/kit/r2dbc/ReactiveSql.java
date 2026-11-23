package pe.axiz.kit.r2dbc;

import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * SQL no bloqueante con parámetros con nombre ({@code :name}). Las implementaciones participan en
 * la transacción de quien llama: el starter de Spring usa el {@code DatabaseClient} consciente de
 * la transacción; {@link R2dbcSql} usa la conexión ligada por {@link R2dbcSql#inTransaction} o por
 * {@link R2dbcTransactionRunner}.
 */
public interface ReactiveSql {

  /**
   * Identidad de la fábrica de conexiones consciente de la transacción; las integraciones
   * desconocidas devuelven null.
   */
  default @Nullable Object transactionResource() {
    return null;
  }

  /** Indica si este recurso SQL participa en la transacción activa del suscriptor. */
  default Mono<Boolean> isTransactionActive() {
    return Mono.just(false);
  }

  /** Ejecuta un INSERT, UPDATE o DELETE y emite la cantidad de filas afectadas. */
  Mono<Long> update(String sql, SqlParams params);

  /** Ejecuta una consulta y emite las filas mapeadas. */
  <T> Flux<T> query(String sql, SqlParams params, RowMapper<T> mapper);

  /** Primera fila de una consulta, o vacío. */
  default <T> Mono<T> queryOne(String sql, SqlParams params, RowMapper<T> mapper) {
    return query(sql, params, mapper).next();
  }
}
