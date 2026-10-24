package pe.axiz.spring.jpa;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Relational defaults (lowest precedence): no open-session-in-view, UTC, batching, schema
 * validation, Flyway, and the AWS Advanced JDBC Wrapper for Aurora (failover, enhanced failure
 * monitoring, optional IAM authentication and read/write splitting for read-only transactions).
 * Outside the {@code local} and {@code test} profiles a schema-changing {@code ddl-auto} fails
 * startup.
 */
public class AxizJpaEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  static final String SOURCE = "axizJpaDefaults";
  private static final List<String> FORBIDDEN_DDL = List.of("create", "create-drop", "update");

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
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
    if (env.getProperty("axiz.jpa.aws-wrapper.enabled", Boolean.class, false)) {
      List<String> plugins = new ArrayList<>(List.of("failover2", "efm2"));
      if (env.getProperty("axiz.jpa.aws-wrapper.iam", Boolean.class, false)) {
        plugins.add("iam");
      }
      if (env.getProperty("axiz.jpa.aws-wrapper.read-write-splitting", Boolean.class, false)) {
        plugins.add(0, "readWriteSplitting");
      }
      d.put("spring.datasource.driver-class-name", "software.amazon.jdbc.Driver");
      d.put(
          "spring.datasource.hikari.data-source-properties.wrapperPlugins",
          String.join(",", plugins));
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
    Set<String> profiles = new HashSet<>(Arrays.asList(env.getActiveProfiles()));
    // whole profile names only: "attestation" or "localstack-prod" are not local profiles
    for (String p : env.getProperty("spring.profiles.active", "").split(",", -1)) {
      profiles.add(p.trim());
    }
    boolean local = profiles.contains("local") || profiles.contains("test");
    if (!local && FORBIDDEN_DDL.contains(ddl)) {
      throw new IllegalStateException(
          "spring.jpa.hibernate.ddl-auto="
              + ddl
              + " is only allowed with the local or test profile; use Flyway migrations and"
              + " ddl-auto=validate");
    }
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
