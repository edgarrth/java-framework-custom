package pe.axiz.structure.batch.spring;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Valores por defecto de batch: sin servidor web (CronJob), el job se ejecuta al arrancar y el
 * proceso termina con su estado; fuera de las ejecuciones locales, Flyway gestiona el esquema de
 * Spring Batch.
 */
public class AxizBatchEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("spring.main.web-application-type", "none");
    d.put("spring.batch.job.enabled", "true");
    d.put("spring.batch.jdbc.initialize-schema", "embedded");
    d.put("spring.threads.virtual.enabled", "true");
    env.getPropertySources().addLast(new MapPropertySource("axizBatchDefaults", d));
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
