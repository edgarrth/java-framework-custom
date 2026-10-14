package pe.axiz.core.persistence.r2dbc;

import io.r2dbc.spi.Connection;
import org.jspecify.annotations.Nullable;

/**
 * Connection bound to the current thread by {@link R2dbcTransactionRunner} for blocking callers
 * (the outbox relay, consumer threads). Reactive code binds it in the Reactor context instead.
 */
final class BoundConnection {

  private static final ThreadLocal<@Nullable Connection> CURRENT = new ThreadLocal<>();

  private BoundConnection() {}

  static @Nullable Connection current() {
    return CURRENT.get();
  }

  static void bind(Connection connection) {
    CURRENT.set(connection);
  }

  static void clear() {
    CURRENT.remove();
  }
}
