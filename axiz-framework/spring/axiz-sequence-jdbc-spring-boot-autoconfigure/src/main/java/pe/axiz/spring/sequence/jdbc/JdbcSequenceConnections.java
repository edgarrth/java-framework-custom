package pe.axiz.spring.sequence.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import pe.axiz.spring.sequence.SequencePoolPolicy;

/** Owns reservation connections independently of the business pool. */
final class JdbcSequenceConnections implements AutoCloseable {
  private final DataSource connections;
  private final @Nullable AutoCloseable owner;

  private JdbcSequenceConnections(DataSource connections, @Nullable AutoCloseable owner) {
    this.connections = connections;
    this.owner = owner;
  }

  DataSource connections() {
    return connections;
  }

  static JdbcSequenceConnections create(
      DataSource source, @Nullable DataSource dedicated, Environment environment) {
    if (dedicated != null) return new JdbcSequenceConnections(dedicated, null);
    if (ClassUtils.isPresent(
        "com.zaxxer.hikari.HikariDataSource", JdbcSequenceConnections.class.getClassLoader()))
      return Hikari.create(source, SequencePoolPolicy.from(environment));
    return new JdbcSequenceConnections(source, null);
  }

  @Override
  public void close() throws Exception {
    if (owner != null) owner.close();
  }

  private static final class Hikari {
    private static JdbcSequenceConnections create(DataSource source, SequencePoolPolicy policy) {
      if (!(source instanceof HikariDataSource main))
        return new JdbcSequenceConnections(source, null);
      if (main.getDataSource() instanceof HikariDataSource nested) return create(nested, policy);
      var settings = new HikariConfig();
      main.copyStateTo(settings);
      var credentials = main.getCredentialsProvider();
      if (credentials != null) {
        settings.setCredentialsProviderClassName(null);
        settings.setCredentialsProvider(credentials);
      } else if (main.getCredentialsProviderClassName() == null) {
        // A password rotated on the main pool must also be used for new counter connections.
        settings.setCredentialsProvider(main::getCredentials);
      }
      settings.setPoolName(main.getPoolName() + "-sequence");
      settings.setMaximumPoolSize(policy.maximum());
      settings.setMinimumIdle(0);
      settings.setConnectionTimeout(policy.acquireTimeout().toMillis());
      var owned = new HikariDataSource(settings);
      return new JdbcSequenceConnections(owned, owned);
    }
  }
}
