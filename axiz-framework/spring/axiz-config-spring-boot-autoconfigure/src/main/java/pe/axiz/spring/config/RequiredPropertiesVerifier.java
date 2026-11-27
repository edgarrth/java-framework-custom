package pe.axiz.spring.config;

import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Hace fallar el arranque cuando falta o está en blanco una propiedad declarada en {@code
 * axiz.config.required}. El mensaje solo lista nombres de propiedades, nunca valores.
 */
public final class RequiredPropertiesVerifier implements InitializingBean {

  private final Environment environment;
  private final List<String> required;

  public RequiredPropertiesVerifier(Environment environment, List<String> required) {
    this.environment = Objects.requireNonNull(environment);
    this.required = List.copyOf(required);
  }

  @Override
  public void afterPropertiesSet() {
    List<String> missing =
        required.stream().filter(k -> !StringUtils.hasText(environment.getProperty(k))).toList();
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "Missing required configuration properties: "
              + String.join(", ", missing)
              + ". Check the service ConfigMap, axiz-common and the mounted secrets.");
    }
  }
}
