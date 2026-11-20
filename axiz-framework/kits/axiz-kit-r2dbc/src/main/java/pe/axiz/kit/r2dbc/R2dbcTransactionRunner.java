package pe.axiz.kit.r2dbc;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
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
    if (timeout.isNegative() || timeout.isZero())
      throw new IllegalArgumentException("Transaction timeout must be positive");
  }

  @Override
  public <T> T inTransaction(Callable<T> work) throws Exception {
    if (BoundConnection.current(connections) != null) {
      return work.call(); // already inside a transaction of this runner: join it
    }
    Connection c = Objects.requireNonNull(Mono.from(connections.create()).block(timeout));
    Throwable primary = null;
    boolean discard = true;
    try {
      Mono.from(c.beginTransaction()).block(timeout);
      BoundConnection.bind(connections, c);
      T result;
      try {
        result = work.call();
      } catch (Exception | Error e) {
        BoundConnection.clear(connections);
        discard = !rollback(c, e);
        throw e;
      }
      BoundConnection.clear(connections);
      try {
        Mono.from(c.commitTransaction()).block(timeout);
      } catch (RuntimeException failure) {
        throw CommitFailures.classify(failure);
      }
      discard = false;
      return result;
    } catch (Exception | Error e) {
      primary = e;
      throw e;
    } finally {
      BoundConnection.clear(connections);
      close(c, primary, discard);
    }
  }

  /** Closes once; cleanup cannot change a confirmed result or hide the primary failure. */
  private void close(Connection c, @Nullable Throwable primary, boolean discard) {
    try {
      (discard ? DiscardConnection.close(c) : Mono.from(c.close())).block(timeout);
    } catch (RuntimeException e) {
      if (primary != null && primary != e) primary.addSuppressed(e);
      LoggerFactory.getLogger(R2dbcTransactionRunner.class)
          .warn("R2DBC transaction cleanup failed; do not retry a confirmed commit");
    }
  }

  private boolean rollback(Connection c, Throwable cause) {
    try {
      Mono.from(c.rollbackTransaction()).block(timeout);
      return true;
    } catch (RuntimeException e) {
      cause.addSuppressed(e);
      return false;
    }
  }
}
