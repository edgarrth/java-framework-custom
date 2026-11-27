package pe.axiz.kit.r2dbc;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import java.util.IdentityHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Conexión ligada al hilo actual por {@link R2dbcTransactionRunner} para llamadores bloqueantes (el
 * relay del outbox, los hilos de consumidores). El código reactivo, en cambio, la liga en el
 * contexto de Reactor.
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
