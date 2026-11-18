package pe.axiz.spring.jpa;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import pe.axiz.core.commons.Environments;

/** JPA and pool defaults; production schema changes require explicit migrations. */
public class AxizJpaEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  static final String SOURCE = "axizJpaDefaults";
  private static final List<String> FORBIDDEN_DDL = List.of("create", "create-drop", "update");
  private static final List<String> SENSITIVE_LOGGERS =
      List.of(
          "org.hibernate.SQL",
          "org.hibernate.SQL_SLOW",
          "org.hibernate.orm.jdbc",
          "org.hibernate.orm.jdbc.bind",
          "org.hibernate.orm.jdbc.extract",
          "org.hibernate.orm.jdbc.error",
          "org.hibernate.orm.jdbc.batch",
          "org.hibernate.engine.jdbc.spi.SqlExceptionHelper");

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
    env.getPropertySources().remove(SOURCE);
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("spring.jpa.open-in-view", "false");
    d.put("spring.jpa.show-sql", "false");
    d.put("spring.jpa.properties.hibernate.show_sql", "false");
    SENSITIVE_LOGGERS.forEach(logger -> d.put("logging.level." + logger, "OFF"));
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
    if (jdbcUrl.startsWith("jdbc:postgresql:"))
      d.put("spring.datasource.hikari.data-source-properties.sslmode", "verify-full");
    String flywayUrl = env.getProperty("spring.flyway.url", jdbcUrl);
    if (flywayUrl.startsWith("jdbc:postgresql:"))
      d.put("spring.flyway.jdbc-properties.sslmode", "verify-full");
    env.getPropertySources().addLast(new MapPropertySource(SOURCE, d));
    verifyDdl(env);
    verifyLogging(env);
  }

  private static void verifyLogging(ConfigurableEnvironment env) {
    if (Environments.allowsInMemoryState(env.getProperty("axiz.config.environment"))) return;
    if (env.getProperty("spring.jpa.show-sql", Boolean.class, false)
        || env.getProperty("spring.jpa.properties.hibernate.show_sql", Boolean.class, false)) {
      throw new IllegalStateException("Hibernate SQL output is forbidden in deployed environments");
    }
    for (String logger : SENSITIVE_LOGGERS) {
      if (!"OFF".equalsIgnoreCase(env.getProperty("logging.level." + logger, "OFF"))) {
        throw new IllegalStateException(
            "logging.level."
                + logger
                + " must be OFF: SQL and driver errors can contain sensitive values");
      }
    }
  }

  static void verifyDdl(ConfigurableEnvironment env) {
    String ddl =
        env.getProperty("spring.jpa.hibernate.ddl-auto", "validate").toLowerCase(Locale.ROOT);
    boolean local = Environments.allowsInMemoryState(env.getProperty("axiz.config.environment"));
    if (!local && FORBIDDEN_DDL.contains(ddl)) {
      throw new IllegalStateException(
          "spring.jpa.hibernate.ddl-auto="
              + ddl
              + " is only allowed with axiz.config.environment=local, test or ci; use Flyway"
              + " migrations and ddl-auto=validate");
    }
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
