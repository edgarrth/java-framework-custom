package pe.axiz.spring.logging;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * Structured logging defaults, applied before the logging system starts. Inside Kubernetes (or with
 * {@code axiz.logging.json=true}) the console writes Boot's native JSON (logstash layout) with
 * {@code trace_id}, {@code span_id} and {@code service}; locally it stays human readable. Every
 * value can be overridden by the service.
 */
public class AxizLoggingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  static final String SOURCE = "axizLoggingDefaults";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
    Map<String, Object> d = new LinkedHashMap<>();
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
