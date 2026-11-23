package pe.axiz.spring.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Valores por defecto de OpenTelemetry (precedencia más baja): exportación OTLP de trazas y
 * métricas al collector indicado por {@code OTEL_EXPORTER_OTLP_ENDPOINT}, sondas de liveness y
 * readiness, y atributos del recurso. No interviene ningún agente Java, por lo que también se
 * aplican a imágenes nativas.
 */
public class AxizObservabilityEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {

  static final String SOURCE = "axizObservabilityDefaults";
  private static final String ENDPOINT = "${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("management.tracing.sampling.probability", "1.0");
    d.put("management.opentelemetry.tracing.export.otlp.endpoint", ENDPOINT + "/v1/traces");
    d.put("management.otlp.metrics.export.url", ENDPOINT + "/v1/metrics");
    d.put("management.otlp.metrics.export.step", "30s");
    d.put(
        "management.opentelemetry.resource-attributes.deployment.environment.name",
        "${axiz.config.environment:default}");
    d.put("management.endpoint.health.probes.enabled", "true");
    d.put("management.endpoint.health.show-details", "never");
    d.put("management.endpoints.web.exposure.include", "health,info");
    d.put("management.info.env.enabled", "false");
    env.getPropertySources().addLast(new MapPropertySource(SOURCE, d));
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
