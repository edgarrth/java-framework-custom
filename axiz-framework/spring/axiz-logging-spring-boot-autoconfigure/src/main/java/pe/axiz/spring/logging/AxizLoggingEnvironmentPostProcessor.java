package pe.axiz.spring.logging;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * Valores por defecto de logging estructurado, aplicados antes de que arranque el sistema de
 * logging. Dentro de Kubernetes (o con {@code axiz.logging.json=true}) la consola escribe el JSON
 * nativo de Boot (layout logstash) con {@code trace_id}, {@code span_id} y {@code service}; en
 * local se mantiene legible para personas. El servicio puede sobrescribir cada valor.
 */
public class AxizLoggingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  static final String SOURCE = "axizLoggingDefaults";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
    Map<String, Object> d = new LinkedHashMap<>();
    if (application.getWebApplicationType() == WebApplicationType.REACTIVE
        || "reactive".equalsIgnoreCase(env.getProperty("spring.main.web-application-type"))) {
      d.put("logging.config", "classpath:pe/axiz/spring/logging/logback-reactive.xml");
    }
    if (json(env)) {
      d.put("logging.structured.format.console", "logstash");
      d.put("logging.structured.json.rename.traceId", "trace_id");
      d.put("logging.structured.json.rename.spanId", "span_id");
      d.put(
          "logging.structured.json.add.service",
          env.getProperty("spring.application.name", "application"));
      d.put("logging.structured.json.stacktrace.max-length", "16384");
    }
    d.put("logging.level.axiz.http", "INFO");
    d.put("logging.level.axiz.operation", "INFO");
    env.getPropertySources().addLast(new MapPropertySource(SOURCE, d));
  }

  static boolean json(ConfigurableEnvironment env) {
    String explicit = env.getProperty("axiz.logging.json");
    if (explicit != null) {
      return Boolean.parseBoolean(explicit);
    }
    boolean local =
        Arrays.asList(env.getActiveProfiles()).contains("local")
            || "local".equals(env.getProperty("spring.profiles.active"));
    return !local && StringUtils.hasText(env.getProperty("KUBERNETES_SERVICE_HOST"));
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
