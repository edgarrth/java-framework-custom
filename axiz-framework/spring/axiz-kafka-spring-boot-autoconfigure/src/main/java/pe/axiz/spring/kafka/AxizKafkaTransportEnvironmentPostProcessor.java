package pe.axiz.spring.kafka;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Valores por defecto de transporte seguro con la precedencia más baja; la guarda comprueba las
 * opciones reales del cliente.
 */
public final class AxizKafkaTransportEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {
  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    String source = "axizKafkaTransportDefaults";
    environment.getPropertySources().remove(source);
    environment
        .getPropertySources()
        .addLast(
            new MapPropertySource(
                source,
                Map.of(
                    "spring.kafka.security.protocol", "SSL",
                    "spring.kafka.properties.ssl.endpoint.identification.algorithm", "https")));
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
