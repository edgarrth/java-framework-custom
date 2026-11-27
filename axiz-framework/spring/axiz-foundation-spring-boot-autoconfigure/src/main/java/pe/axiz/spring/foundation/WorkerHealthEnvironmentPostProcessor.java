package pe.axiz.spring.foundation;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.ClassUtils;

/**
 * Añade la salud de los workers al grupo de readiness por defecto; la configuración explícita de la
 * aplicación tiene precedencia.
 */
public final class WorkerHealthEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {
  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (ClassUtils.isPresent(
            "org.springframework.boot.health.contributor.HealthIndicator",
            getClass().getClassLoader())
        && environment.getProperty(
            "management.endpoint.health.probes.enabled", Boolean.class, true))
      environment
          .getPropertySources()
          .addLast(
              new MapPropertySource(
                  "axizWorkerReadinessDefaults",
                  Map.of(
                      "management.endpoint.health.probes.enabled",
                      "true",
                      "management.endpoint.health.group.readiness.include",
                      "readinessState,axizWorkers")));
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
