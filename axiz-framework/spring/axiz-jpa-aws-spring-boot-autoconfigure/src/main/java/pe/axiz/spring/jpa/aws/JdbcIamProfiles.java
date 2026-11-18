package pe.axiz.spring.jpa.aws;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import software.amazon.jdbc.ConnectionPluginFactory;
import software.amazon.jdbc.plugin.efm.v2.HostMonitoringConnectionPluginV2Factory;
import software.amazon.jdbc.plugin.failover2.FailoverConnectionPluginFactory;
import software.amazon.jdbc.plugin.readwritesplitting.ReadWriteSplittingPluginFactory;
import software.amazon.jdbc.profile.ConfigurationProfileBuilder;
import software.amazon.jdbc.profile.DriverConfigurationProfiles;

/** Two immutable plugin definitions contain no credentials or resources shared across contexts. */
final class JdbcIamProfiles {
  private JdbcIamProfiles() {}

  static String register(boolean split) {
    String name = split ? "axiz-jpa-iam-split" : "axiz-jpa-iam";
    List<Class<? extends ConnectionPluginFactory>> factories =
        new ArrayList<>(
            List.of(
                FailoverConnectionPluginFactory.class,
                HostMonitoringConnectionPluginV2Factory.class));
    if (split) factories.add(ReadWriteSplittingPluginFactory.class);
    factories.add(AxizJdbcIamPluginFactory.class);
    DriverConfigurationProfiles.addOrReplaceProfile(
        name,
        ConfigurationProfileBuilder.get()
            .withName(name)
            .withProperties(new Properties())
            .withPluginFactories(factories)
            .build());
    return name;
  }
}
