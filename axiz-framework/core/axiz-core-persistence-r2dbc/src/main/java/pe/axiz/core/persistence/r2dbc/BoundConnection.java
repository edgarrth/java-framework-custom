package pe.axiz.core.persistence.r2dbc;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import java.util.IdentityHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Connection bound to the current thread by {@link R2dbcTransactionRunner} for blocking callers
 * (the outbox relay, consumer threads). Reactive code binds it in the Reactor context instead.
 */
final class BoundConnection {

  private static final ThreadLocal<@Nullable IdentityHashMap<ConnectionFactory, Connection>>
      CURRENT = new ThreadLocal<>();

  private BoundConnection() {}

  static @Nullable Connection current(ConnectionFactory factory) {
    var connections = CURRENT.get();
    return connections == null ? null : connections.get(factory);
  }

  static void bind(ConnectionFactory factory, Connection connection) {
    var connections = CURRENT.get();
    if (connections == null) {
      connections = new IdentityHashMap<>();
      CURRENT.set(connections);
    }
    connections.put(factory, connection);
  }

  static void clear(ConnectionFactory factory) {
    var connections = CURRENT.get();
    if (connections != null) {
      connections.remove(factory);
      if (connections.isEmpty()) {
        CURRENT.remove();
      }
    }
  }
}
