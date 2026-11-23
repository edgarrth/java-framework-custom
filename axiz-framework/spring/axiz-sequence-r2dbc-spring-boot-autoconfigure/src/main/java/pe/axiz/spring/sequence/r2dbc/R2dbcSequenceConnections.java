package pe.axiz.spring.sequence.r2dbc;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Wrapped;
import java.util.Collections;
import java.util.IdentityHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;
import pe.axiz.spring.sequence.SequencePoolPolicy;

/** Posee las conexiones de reserva con independencia del pool de negocio. */
final class R2dbcSequenceConnections implements AutoCloseable {
  private final ConnectionFactory connections;
  private final @Nullable ConnectionPool owner;

  private R2dbcSequenceConnections(ConnectionFactory connections, @Nullable ConnectionPool owner) {
    this.connections = connections;
    this.owner = owner;
  }

  ConnectionFactory connections() {
    return connections;
  }

  static R2dbcSequenceConnections create(
      ConnectionFactory source, @Nullable ConnectionFactory dedicated, Environment environment) {
    if (dedicated != null) return new R2dbcSequenceConnections(dedicated, null);
    var seen = Collections.newSetFromMap(new IdentityHashMap<ConnectionFactory, Boolean>());
    while (source instanceof Wrapped<?> wrapped) {
      if (!seen.add(source))
        throw new IllegalArgumentException("Cyclic reservation connection factory");
      Object target = wrapped.unwrap();
      if (!(target instanceof ConnectionFactory factory)) break;
      source = factory;
    }
    SequencePoolPolicy policy = SequencePoolPolicy.from(environment);
    var owned =
        new ConnectionPool(
            ConnectionPoolConfiguration.builder(source)
                .name("axiz-sequence")
                .initialSize(0)
                .maxSize(policy.maximum())
                .maxAcquireTime(policy.acquireTimeout())
                .maxCreateConnectionTime(policy.acquireTimeout())
                .build());
    return new R2dbcSequenceConnections(owned, owned);
  }

  @Override
  public void close() {
    if (owner != null) owner.dispose();
  }
}
