package pe.axiz.spring.jpa.aws;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import pe.axiz.adapter.aws.transport.AwsClientSettings;

/**
 * Convenciones opcionales del wrapper de AWS, incluidos TLS y ámbitos de credenciales IAM acotados.
 */
public final class AxizJpaAwsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
  private static final String SOURCE = "axizJpaAwsDefaults";

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
    env.getPropertySources().remove(SOURCE);
    Map<String, Object> d = new LinkedHashMap<>();
    if (env.getProperty("spring.datasource.url", "").startsWith("jdbc:aws-wrapper:postgresql:"))
      d.put("spring.datasource.hikari.data-source-properties.sslmode", "verify-full");
    if (env.getProperty("spring.flyway.url", env.getProperty("spring.datasource.url", ""))
        .startsWith("jdbc:aws-wrapper:postgresql:"))
      d.put("spring.flyway.jdbc-properties.sslmode", "verify-full");
    if (env.getProperty("axiz.jpa.aws-wrapper.enabled", Boolean.class, false)) {
      List<String> plugins = new ArrayList<>(List.of("failover2", "efm2"));
      boolean iam = env.getProperty("axiz.jpa.aws-wrapper.iam", Boolean.class, false);
      boolean split =
          env.getProperty("axiz.jpa.aws-wrapper.read-write-splitting", Boolean.class, false);
      d.put("spring.datasource.driver-class-name", "software.amazon.jdbc.Driver");
      String pluginProperty = "spring.datasource.hikari.data-source-properties.wrapperPlugins";
      String profileProperty = "spring.datasource.hikari.data-source-properties.wrapperProfileName";
      if (iam) {
        if (env.containsProperty(pluginProperty) || env.containsProperty(profileProperty))
          throw new IllegalStateException(
              "Framework JDBC IAM owns its wrapper profile; remove custom"
                  + " wrapperPlugins/wrapperProfileName or configure custom authentication without"
                  + " axiz.jpa.aws-wrapper.iam");
        d.put(profileProperty, JdbcIamProfiles.register(split));
        var region =
            AwsClientSettings.resolveRegion(
                env::getProperty, AwsClientSettings.RegionPolicy.SDK_CHAIN);
        if (region != null)
          d.put("spring.datasource.hikari.data-source-properties.iamRegion", region.id());
      } else {
        if (split) plugins.add(0, "readWriteSplitting");
        d.put(pluginProperty, String.join(",", plugins));
      }
      d.put("spring.datasource.hikari.data-source-properties.wrapperDialect", "aurora-pg");
      d.put(
          "spring.datasource.hikari.exception-override-class-name",
          "software.amazon.jdbc.util.HikariCPSQLException");
    }
    env.getPropertySources().addLast(new MapPropertySource(SOURCE, d));
  }
}
