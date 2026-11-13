package pe.axiz.spring.jpa;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.jdbc.ConnectionPlugin;
import software.amazon.jdbc.ConnectionPluginFactory;
import software.amazon.jdbc.HostSpec;
import software.amazon.jdbc.JdbcCallable;
import software.amazon.jdbc.PropertyDefinition;
import software.amazon.jdbc.plugin.AbstractConnectionPlugin;
import software.amazon.jdbc.plugin.iam.IamAuthConnectionPlugin;
import software.amazon.jdbc.util.FullServicesContainer;
import software.amazon.jdbc.util.IamAuthUtils;
import software.amazon.jdbc.util.RegionUtils;

/** JDBC wrapper SPI: each connection borrows the managed pool scope identity. */
public final class AxizJdbcIamPluginFactory implements ConnectionPluginFactory {
  @Override
  public ConnectionPlugin getInstance(FullServicesContainer services, Properties properties) {
    String name = PropertyDefinition.PROFILE_NAME.getString(properties);
    var configuration = services.getConfigurationProfile();
    if (name == null && configuration != null) name = configuration.getName();
    if (name != null && name.startsWith(JdbcIamCredentialScopes.PREFIX)) {
      AwsCredentialsProvider provider = JdbcIamCredentialScopes.find(name);
      if (provider == null) throw new IllegalStateException("JDBC IAM credential scope is closed");
      return new Plugin(provider);
    }
    throw new IllegalStateException(
        "Framework JDBC IAM requires a managed credential scope; register an unstarted Hikari pool"
            + " or configure a different authentication integration");
  }

  static final class Plugin extends AbstractConnectionPlugin {
    private final AwsCredentialsProvider source;

    Plugin(AwsCredentialsProvider source) {
      this.source = Objects.requireNonNull(source);
    }

    @Override
    public Set<String> getSubscribedMethods() {
      return Set.of("connect", "forceConnect");
    }

    @Override
    public Connection connect(
        String protocol,
        HostSpec host,
        Properties properties,
        boolean initial,
        JdbcCallable<Connection, SQLException> connect)
        throws SQLException {
      try {
        String profile = PropertyDefinition.AWS_PROFILE.getString(properties);
        if (properties.containsKey(PropertyDefinition.AWS_PROFILE.name)
            && profile != null
            && !profile.isBlank())
          throw new SQLException(
              "Framework JDBC IAM does not select a per-connection awsProfile; configure the"
                  + " workload identity or custom authentication explicitly",
              "HY000");
        String user = PropertyDefinition.USER.getString(properties);
        if (user == null || user.isBlank())
          throw new SQLException("JDBC IAM requires a database user", "28000");
        var iamHost =
            IamAuthUtils.getIamHost(IamAuthConnectionPlugin.IAM_HOST.getString(properties), host);
        int port =
            IamAuthUtils.getIamPort(
                IamAuthConnectionPlugin.IAM_DEFAULT_PORT.getInteger(properties), iamHost, 5432);
        var region =
            new RegionUtils()
                .getRegion(iamHost, properties, IamAuthConnectionPlugin.IAM_REGION.name);
        if (region == null)
          throw new SQLException("JDBC IAM requires iamRegion or an RDS hostname", "HY000");
        String token =
            IamAuthUtils.getTokenUtility()
                .generateAuthenticationToken(source, region, iamHost.getHost(), port, user);
        String property =
            Objects.requireNonNull(
                IamAuthConnectionPlugin.IAM_TOKEN_PROPERTY_NAME.getString(properties),
                "JDBC IAM token property name is required");
        properties.setProperty(property, token);
        // Runs on every physical connect, including wrapper failover/reconnect after token expiry.
        return connect.call();
      } catch (RuntimeException failure) {
        throw new SQLException("Unable to obtain JDBC IAM credentials", "28000", failure);
      } catch (SQLException failure) {
        throw failure;
      }
    }

    @Override
    public Connection forceConnect(
        String protocol,
        HostSpec host,
        Properties properties,
        boolean initial,
        JdbcCallable<Connection, SQLException> connect)
        throws SQLException {
      return connect(protocol, host, properties, initial, connect);
    }
  }
}
