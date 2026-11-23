package pe.axiz.spring.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Valores web seguros por defecto con la precedencia más baja: hilos virtuales, apagado ordenado,
 * sin stack traces ni mensajes de excepción en las respuestas de error, los problem details propios
 * de Boot desactivados (el starter de errores se encarga de ellos) y metadatos OpenAPI a partir del
 * nombre de la aplicación.
 */
public class AxizWebEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  static final String SOURCE = "axizWebDefaults";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("spring.threads.virtual.enabled", "true");
    d.put("server.shutdown", "graceful");
    d.put("server.error.include-stacktrace", "never");
    d.put("server.error.include-message", "never");
    d.put("server.error.include-binding-errors", "never");
    d.put("server.error.whitelabel.enabled", "false");
    d.put("spring.mvc.problemdetails.enabled", "false");
    d.put("spring.webflux.problemdetails.enabled", "false");
    d.put("springdoc.open-api.info.title", "${spring.application.name:application}");
    d.put("springdoc.writer-with-default-pretty-printer", "false");
    env.getPropertySources().addLast(new MapPropertySource(SOURCE, d));
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
