package pe.axiz.spring.r2dbc;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import pe.axiz.core.persistence.r2dbc.R2dbcUrls;

/**
 * R2DBC defaults (lowest precedence) suited to Aurora: connections are validated against the server
 * when borrowed and renewed every 10 minutes, so a failover or an expired IAM token never leaves
 * dead connections in the pool; acquiring a connection is bounded so an exhausted pool fails fast
 * instead of queueing requests forever. Flyway, which migrates over JDBC, gets its URL and
 * credentials from {@code spring.r2dbc.*} unless they are set explicitly.
 */
public class AxizR2dbcEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  static final String SOURCE = "axizR2dbcDefaults";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("spring.r2dbc.pool.initial-size", "2");
    d.put("spring.r2dbc.pool.min-idle", "2");
    d.put("spring.r2dbc.pool.max-size", "20");
    d.put("spring.r2dbc.pool.max-life-time", "10m");
    d.put("spring.r2dbc.pool.max-idle-time", "5m");
    d.put("spring.r2dbc.pool.max-acquire-time", "5s");
    d.put("spring.r2dbc.pool.max-create-connection-time", "10s");
    d.put("spring.r2dbc.pool.max-validation-time", "2s");
    d.put("spring.r2dbc.pool.validation-query", "SELECT 1");
    d.put("spring.r2dbc.pool.validation-depth", "remote");
    d.put("spring.r2dbc.properties.applicationName", "${spring.application.name:axiz}");
    d.put("spring.flyway.locations", "classpath:db/migration");
    if (env.getProperty("axiz.r2dbc.iam.enabled", Boolean.class, false)) {
      // Aurora accepts IAM tokens only over TLS
      d.put("spring.r2dbc.properties.sslMode", "require");
    }
    String url = env.getProperty("spring.r2dbc.url");
    if (url != null
        && env.getProperty("axiz.r2dbc.flyway-from-r2dbc", Boolean.class, true)
        && !env.containsProperty("spring.flyway.url")
        && url.contains("postgresql")) {
      d.put("spring.flyway.url", R2dbcUrls.toJdbc(url));
      d.put("spring.flyway.user", "${spring.r2dbc.username:}");
      if (!env.getProperty("axiz.r2dbc.iam.enabled", Boolean.class, false)) {
        d.put("spring.flyway.password", "${spring.r2dbc.password:}");
      }
    }
    env.getPropertySources().addLast(new MapPropertySource(SOURCE, d));
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
