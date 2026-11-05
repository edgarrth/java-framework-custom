package pe.axiz.kit.r2dbc;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.tx.TransactionRunner;
import reactor.core.publisher.Mono;

/**
 * Transactions for blocking callers over R2DBC (the outbox relay and message consumers, which run
 * on their own threads, never on an event loop): the connection is bound to the thread while the
 * work runs, so {@link R2dbcSql} calls made by the work join the transaction.
 */
public final class R2dbcTransactionRunner implements TransactionRunner {

  private final ConnectionFactory connections;
  private final Duration timeout;

  public R2dbcTransactionRunner(ConnectionFactory connections, Duration timeout) {
    this.connections = Objects.requireNonNull(connections);
    this.timeout = Objects.requireNonNull(timeout);
  }

  @Override
  public <T> T inTransaction(Callable<T> work) throws Exception {
    if (BoundConnection.current(connections) != null) {
      return work.call(); // already inside a transaction of this runner: join it
    }
    Connection c = Objects.requireNonNull(Mono.from(connections.create()).block(timeout));
    Throwable primary = null;
    try {
      Mono.from(c.beginTransaction()).block(timeout);
      BoundConnection.bind(connections, c);
      T result;
      try {
        result = work.call();
      } catch (Exception | Error e) {
        BoundConnection.clear(connections);
        rollback(c, e);
        throw e;
      }
      BoundConnection.clear(connections);
      Mono.from(c.commitTransaction()).block(timeout);
      return result;
    } catch (Exception | Error e) {
      primary = e;
      throw e;
    } finally {
      BoundConnection.clear(connections);
      close(c, primary);
    }
  }

  /** Closes exactly once; a close failure never hides the error of the transaction. */
  private void close(Connection c, @Nullable Throwable primary) {
    try {
      Mono.from(c.close()).block(timeout);
    } catch (RuntimeException e) {
      if (primary == null) {
        throw e;
      }
      primary.addSuppressed(e);
    }
  }

  private void rollback(Connection c, Throwable cause) {
    try {
      Mono.from(c.rollbackTransaction()).block(timeout);
    } catch (RuntimeException e) {
      cause.addSuppressed(e);
    }
  }
}
