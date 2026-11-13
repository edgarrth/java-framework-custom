package pe.axiz.spring.jpa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import pe.axiz.adapter.aws.transport.AwsClientSettings;
import pe.axiz.core.commons.Environments;

/**
 * Relational defaults (lowest precedence): no open-session-in-view, UTC, batching, schema
 * validation, Flyway, and the AWS Advanced JDBC Wrapper for Aurora (failover, enhanced failure
 * monitoring, optional IAM authentication and read/write splitting for read-only transactions).
 * Outside explicit single-process environments a schema-changing {@code ddl-auto} fails startup.
 */
public class AxizJpaEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  static final String SOURCE = "axizJpaDefaults";
  private static final List<String> FORBIDDEN_DDL = List.of("create", "create-drop", "update");

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
    env.getPropertySources().remove(SOURCE);
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("spring.jpa.open-in-view", "false");
    d.put("spring.jpa.hibernate.ddl-auto", "validate");
    d.put("spring.jpa.properties.hibernate.jdbc.time_zone", "UTC");
    d.put("spring.jpa.properties.hibernate.jdbc.batch_size", "50");
    d.put("spring.jpa.properties.hibernate.order_inserts", "true");
    d.put("spring.jpa.properties.hibernate.order_updates", "true");
    d.put("spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch", "true");
    d.put("spring.jpa.properties.hibernate.query.in_clause_parameter_padding", "true");
    d.put("spring.flyway.locations", "classpath:db/migration");
    d.put("spring.datasource.hikari.maximum-pool-size", "20");
    String jdbcUrl = env.getProperty("spring.datasource.url", "");
    if (jdbcUrl.startsWith("jdbc:postgresql:")
        || jdbcUrl.startsWith("jdbc:aws-wrapper:postgresql:"))
      d.put("spring.datasource.hikari.data-source-properties.sslmode", "verify-full");
    String flywayUrl = env.getProperty("spring.flyway.url", jdbcUrl);
    if (flywayUrl.startsWith("jdbc:postgresql:")
        || flywayUrl.startsWith("jdbc:aws-wrapper:postgresql:"))
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
    verifyDdl(env);
  }

  static void verifyDdl(ConfigurableEnvironment env) {
    String ddl =
        env.getProperty("spring.jpa.hibernate.ddl-auto", "validate").toLowerCase(Locale.ROOT);
    boolean local = Environments.allowsInMemoryState(env.getProperty("axiz.config.environment"));
    if (!local && FORBIDDEN_DDL.contains(ddl)) {
      throw new IllegalStateException(
          "spring.jpa.hibernate.ddl-auto="
              + ddl
              + " is only allowed with axiz.config.environment=local, test or ci; use Flyway migrations and"
              + " ddl-auto=validate");
    }
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
