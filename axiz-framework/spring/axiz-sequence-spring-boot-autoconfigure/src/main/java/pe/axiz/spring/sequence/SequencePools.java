package pe.axiz.spring.sequence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Wrapped;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/** Owns reservation connections independently of the saturated business pool. */
final class SequencePools {
  private SequencePools() {}

  private record Policy(int maximum, Duration acquireTimeout) {
    private Policy {
      if (maximum < 1 || maximum > 32 || acquireTimeout.toMillis() < 250)
        throw new IllegalArgumentException(
            "Sequence pool requires max-size 1..32 and acquire-timeout >=250ms");
    }

    static Policy from(Environment environment) {
      Binder binder = Binder.get(environment);
      return new Policy(
          Objects.requireNonNull(
              binder.bind("axiz.sequence.pool.max-size", Integer.class).orElse(2)),
          Objects.requireNonNull(
              binder
                  .bind("axiz.sequence.pool.acquire-timeout", Duration.class)
                  .orElse(Duration.ofSeconds(5))));
    }
  }

  static final class Jdbc implements AutoCloseable {
    private final DataSource connections;
    private final @Nullable AutoCloseable owner;

    private Jdbc(DataSource connections, @Nullable AutoCloseable owner) {
      this.connections = connections;
      this.owner = owner;
    }

    DataSource connections() {
      return connections;
    }

    static Jdbc create(DataSource source, @Nullable DataSource dedicated, Environment environment) {
      if (dedicated != null) return new Jdbc(dedicated, null);
      if (ClassUtils.isPresent(
          "com.zaxxer.hikari.HikariDataSource", SequencePools.class.getClassLoader()))
        return Hikari.create(source, Policy.from(environment));
      return new Jdbc(source, null);
    }

    @Override
    public void close() throws Exception {
      if (owner != null) owner.close();
    }
  }

  private static final class Hikari {
    private static Jdbc create(DataSource source, Policy policy) {
      if (!(source instanceof HikariDataSource main)) return new Jdbc(source, null);
      if (main.getDataSource() instanceof HikariDataSource nested) return create(nested, policy);
      var settings = new HikariConfig();
      main.copyStateTo(settings);
      var credentials = main.getCredentialsProvider();
      if (credentials != null) {
        settings.setCredentialsProviderClassName(null);
        settings.setCredentialsProvider(credentials::getCredentials);
      } else if (main.getCredentialsProviderClassName() == null) {
        // A password rotated on the main pool must also be used for new counter connections.
        settings.setCredentialsProvider(main::getCredentials);
      }
      settings.setPoolName(main.getPoolName() + "-sequence");
      settings.setMaximumPoolSize(policy.maximum());
      settings.setMinimumIdle(0);
      settings.setConnectionTimeout(policy.acquireTimeout().toMillis());
      var owned = new HikariDataSource(settings);
      return new Jdbc(owned, owned);
    }
  }

  static final class Reactive implements AutoCloseable {
    private final ConnectionFactory connections;
    private final @Nullable ConnectionPool owner;

    private Reactive(ConnectionFactory connections, @Nullable ConnectionPool owner) {
      this.connections = connections;
      this.owner = owner;
    }

    ConnectionFactory connections() {
      return connections;
    }

    static Reactive create(
        ConnectionFactory source, @Nullable ConnectionFactory dedicated, Environment environment) {
      if (dedicated != null) return new Reactive(dedicated, null);
      var seen = Collections.newSetFromMap(new IdentityHashMap<ConnectionFactory, Boolean>());
      while (source instanceof Wrapped<?> wrapped) {
        if (!seen.add(source))
          throw new IllegalArgumentException("Cyclic reservation connection factory");
        Object target = wrapped.unwrap();
        if (!(target instanceof ConnectionFactory factory)) break;
        source = factory;
      }
      Policy policy = Policy.from(environment);
      var owned =
          new ConnectionPool(
              ConnectionPoolConfiguration.builder(source)
                  .name("axiz-sequence")
                  .initialSize(0)
                  .maxSize(policy.maximum())
                  .maxAcquireTime(policy.acquireTimeout())
                  .maxCreateConnectionTime(policy.acquireTimeout())
                  .build());
      return new Reactive(owned, owned);
    }

    @Override
    public void close() {
      if (owner != null) owner.dispose();
    }
  }
}
