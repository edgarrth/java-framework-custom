package pe.axiz.kit.r2dbc;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * {@link ReactiveSql} directamente sobre una {@link ConnectionFactory} de R2DBC (normalmente el
 * pool). Una llamada usa, en orden: la conexión de {@link #inTransaction} (contexto de Reactor), la
 * conexión ligada al hilo por {@link R2dbcTransactionRunner} o una conexión nueva cerrada tras la
 * llamada.
 */
public final class R2dbcSql implements ReactiveSql {

  /**
   * Clave del contexto de Reactor para los enlaces con alcance de fábrica, gestionados por {@link
   * #inTransaction}.
   */
  public static final String CONNECTION_KEY = "pe.axiz.r2dbc.connection";

  private final ConnectionFactory connections;

  public R2dbcSql(ConnectionFactory connections) {
    this.connections = Objects.requireNonNull(connections);
  }

  @Override
  public Object transactionResource() {
    return connections;
  }

  @Override
  public Mono<Boolean> isTransactionActive() {
    return Mono.deferContextual(
        context -> {
          Connection connection = shared(context);
          return Mono.just(connection != null && !connection.isAutoCommit());
        });
  }

  @Override
  public Mono<Long> update(String sql, SqlParams params) {
    NamedSql named = NamedSql.parse(sql);
    return withConnection(
            c -> Flux.from(statement(c, named, params).execute()).concatMap(Result::getRowsUpdated))
        .reduce(0L, Long::sum);
  }

  @Override
  public <T> Flux<T> query(String sql, SqlParams params, RowMapper<T> mapper) {
    NamedSql named = NamedSql.parse(sql);
    return withConnection(
        c ->
            Flux.from(statement(c, named, params).execute())
                .concatMap(r -> r.map((row, meta) -> mapper.map(new R2dbcRow(row)))));
  }

  /**
   * Ejecuta el trabajo en una transacción: commit cuando termina, rollback ante error o
   * cancelación. Las llamadas de esta instancia dentro del trabajo usan la conexión de la
   * transacción.
   *
   * <p>La conexión se cierra una vez en cada resultado. Un commit no confirmado tiene un resultado
   * desconocido explícito y nunca se informa como rollback. La limpieza no puede cambiar un
   * resultado confirmado. El trabajo anidado para la misma fábrica se une a la transacción; solo su
   * propietario hace commit.
   */
  public <T> Mono<T> inTransaction(Function<ReactiveSql, Mono<T>> work) {
    return transaction(work, true);
  }

  /**
   * Usa una conexión y un commit independientes incluso dentro de una transacción existente. Para
   * reservas durables como los bloques de secuencias; se preservan la transacción de quien llama y
   * el resto del contexto.
   */
  public <T> Mono<T> inIndependentTransaction(Function<ReactiveSql, Mono<T>> work) {
    return transaction(work, false);
  }

  private <T> Mono<T> transaction(Function<ReactiveSql, Mono<T>> work, boolean join) {
    Objects.requireNonNull(work);
    return Mono.deferContextual(
        context -> {
          if (join && shared(context) != null) return Mono.defer(() -> work.apply(this));
          var committing = new AtomicBoolean();
          return Mono.usingWhen(
              Mono.defer(() -> Mono.from(connections.create())),
              c ->
                  Mono.defer(() -> Mono.from(c.beginTransaction()))
                      .then(Mono.defer(() -> work.apply(this)))
                      .contextWrite(ctx -> bind(ctx, c))
                      .map(Optional::of)
                      .defaultIfEmpty(Optional.empty())
                      .flatMap(
                          result ->
                              Mono.defer(
                                      () -> {
                                        committing.set(true);
                                        return Mono.from(c.commitTransaction());
                                      })
                                  .onErrorMap(CommitFailures::classify)
                                  .then(Mono.justOrEmpty(result))),
              c -> quietly(c::close, null),
              (c, error) ->
                  committing.get()
                      ? quietly(() -> DiscardConnection.close(c), error)
                      : rollbackAndClose(c, error),
              c ->
                  committing.get()
                      ? quietly(() -> DiscardConnection.close(c), null)
                      : rollbackAndClose(c, null));
        });
  }

  /**
   * Lleva la transacción de un worker imperativo a través de toda la composición reactiva. Se llama
   * solo en la frontera bloqueante, en el hilo propiedad de R2dbcTransactionRunner. Capturar cada
   * sentencia por separado pierde el enlace cuando flatMap se reanuda en un hilo del driver.
   */
  public <T> Mono<T> withCallingTransaction(Mono<T> work) {
    Connection connection = BoundConnection.current(connections);
    return connection == null ? work : work.contextWrite(context -> bind(context, connection));
  }

  static Mono<Void> rollbackAndClose(Connection c, @Nullable Throwable error) {
    return Mono.defer(() -> Mono.from(c.rollbackTransaction()))
        .thenReturn(true)
        .onErrorResume(
            failure -> {
              if (error != null && error != failure) error.addSuppressed(failure);
              LoggerFactory.getLogger(R2dbcSql.class)
                  .warn("R2DBC rollback failed; discarding connection");
              return Mono.just(false);
            })
        .flatMap(
            rolledBack ->
                quietly(() -> rolledBack ? c.close() : DiscardConnection.close(c), error));
  }

  private static Mono<Void> quietly(Supplier<Publisher<Void>> step, @Nullable Throwable error) {
    return Mono.defer(() -> Mono.from(step.get()))
        .onErrorResume(
            e -> {
              if (error != null && error != e) {
                error.addSuppressed(e);
              }
              LoggerFactory.getLogger(R2dbcSql.class)
                  .warn("R2DBC cleanup failed; transaction outcome unchanged");
              return Mono.empty();
            });
  }

  private <T> Flux<T> withConnection(Function<Connection, Flux<T>> work) {
    return Flux.deferContextual(
        ctx -> {
          Connection shared = shared(ctx);
          if (shared != null) {
            return work.apply(shared);
          }
          return Flux.usingWhen(
              connections.create(),
              work,
              c -> quietly(c::close, null),
              (c, error) -> quietly(c::close, error),
              c -> quietly(c::close, null));
        });
  }

  private Context bind(Context ctx, Connection connection) {
    Map<ConnectionFactory, Connection> existing =
        Objects.requireNonNull(ctx.getOrDefault(CONNECTION_KEY, Map.of()));
    IdentityHashMap<ConnectionFactory, Connection> scoped = new IdentityHashMap<>();
    existing.forEach(scoped::put);
    scoped.put(connections, connection);
    return ctx.put(CONNECTION_KEY, Collections.unmodifiableMap(scoped));
  }

  private @Nullable Connection shared(ContextView ctx) {
    if (ctx.hasKey(CONNECTION_KEY)) {
      Map<ConnectionFactory, Connection> scoped = ctx.get(CONNECTION_KEY);
      Connection connection = scoped.get(connections);
      if (connection != null) {
        return connection;
      }
    }
    return BoundConnection.current(connections);
  }

  static Statement statement(Connection c, NamedSql named, SqlParams params) {
    Statement s = c.createStatement(named.sql());
    for (int i = 0; i < named.names().size(); i++) {
      SqlParams.Param p = params.get(named.names().get(i));
      Object value = p.value();
      if (value == null) {
        s.bindNull(i, p.type());
      } else {
        s.bind(i, value);
      }
    }
    return s;
  }
}
