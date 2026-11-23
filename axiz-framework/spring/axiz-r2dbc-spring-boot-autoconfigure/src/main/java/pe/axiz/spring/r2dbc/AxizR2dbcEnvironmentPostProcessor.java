package pe.axiz.spring.r2dbc;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import pe.axiz.kit.r2dbc.R2dbcUrls;

/**
 * Valores por defecto de R2DBC (precedencia más baja) adecuados para Aurora: las conexiones se
 * validan contra el servidor al tomarlas prestadas y se renuevan cada 10 minutos, de modo que un
 * failover o un token IAM expirado nunca dejan conexiones muertas en el pool; la adquisición de una
 * conexión está acotada para que un pool agotado falle de inmediato en lugar de encolar solicitudes
 * indefinidamente. Flyway, que migra sobre JDBC, obtiene su URL y sus credenciales de {@code
 * spring.r2dbc.*} salvo que se configuren explícitamente.
 */
public class AxizR2dbcEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  static final String SOURCE = "axizR2dbcDefaults";

  @SuppressFBWarnings(
      value = "HARD_CODE_PASSWORD",
      justification =
          "An empty Flyway placeholder selects the managed JDBC IAM credential integration; "
              + "it is not an embedded password.")
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
    d.put("spring.r2dbc.properties.sslMode", "verify-full");
    String url = env.getProperty("spring.r2dbc.url");
    if (url != null
        && env.getProperty("axiz.r2dbc.flyway-from-r2dbc", Boolean.class, true)
        && !env.containsProperty("spring.flyway.url")
        && url.contains("postgresql")) {
      d.put("spring.flyway.url", R2dbcUrls.toJdbc(url));
      d.put(
          "spring.flyway.jdbc-properties.sslmode",
          env.getProperty("spring.r2dbc.properties.sslMode", "verify-full"));
      String rootCert = env.getProperty("spring.r2dbc.properties.sslRootCert");
      if (rootCert != null) d.put("spring.flyway.jdbc-properties.sslrootcert", rootCert);
      d.put("spring.flyway.user", "${spring.r2dbc.username:}");
      if (!"dynamic".equals(env.getProperty("axiz.r2dbc.password-mode", "static"))) {
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
