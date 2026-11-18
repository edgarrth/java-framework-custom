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
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * {@link ReactiveSql} directly over an R2DBC {@link ConnectionFactory} (normally the pool). A call
 * uses, in order: the connection of {@link #inTransaction} (Reactor context), the connection bound
 * to the thread by {@link R2dbcTransactionRunner}, or a new connection closed after the call.
 */
public final class R2dbcSql implements ReactiveSql {

  /** Reactor context key of factory-scoped bindings, managed by {@link #inTransaction}. */
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
   * Runs the work in one transaction: commit when it completes, rollback on error or cancel. Calls
   * of this instance inside the work use the transaction connection.
   *
   * <p>The connection is closed exactly once in every outcome. A failed commit is the error of the
   * returned {@code Mono}; a rollback or close failure after an error is added to that error as
   * suppressed instead of replacing it.
   */
  public <T> Mono<T> inTransaction(Function<ReactiveSql, Mono<T>> work) {
    return Mono.usingWhen(
        Mono.from(connections.create()),
        c ->
            Mono.from(c.beginTransaction())
                .then(Mono.defer(() -> work.apply(this)))
                .contextWrite(ctx -> bind(ctx, c))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(result -> Mono.from(c.commitTransaction()).then(Mono.justOrEmpty(result))),
        c -> Mono.from(c.close()),
        (c, error) -> rollbackAndClose(c, error),
        c -> rollbackAndClose(c, null));
  }

  /**
   * Carries the transaction of an imperative worker through the entire reactive composition. Call
   * only at the blocking boundary, on the thread owned by R2dbcTransactionRunner. Capturing each
   * statement separately loses the binding when flatMap resumes on a driver thread.
   */
  public <T> Mono<T> withCallingTransaction(Mono<T> work) {
    Connection connection = BoundConnection.current(connections);
    return connection == null ? work : work.contextWrite(context -> bind(context, connection));
  }

  static Mono<Void> rollbackAndClose(Connection c, @Nullable Throwable error) {
    return quietly(c.rollbackTransaction(), error)
        .then(Mono.defer(() -> quietly(c.close(), error)));
  }

  private static Mono<Void> quietly(Publisher<Void> step, @Nullable Throwable error) {
    return Mono.from(step)
        .onErrorResume(
            e -> {
              if (error != null && error != e) {
                error.addSuppressed(e);
              }
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
          return Flux.usingWhen(connections.create(), work, Connection::close);
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
