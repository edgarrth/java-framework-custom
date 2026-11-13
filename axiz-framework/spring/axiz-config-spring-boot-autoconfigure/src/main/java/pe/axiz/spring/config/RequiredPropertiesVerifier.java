package pe.axiz.spring.config;

import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Fails startup when a property declared in {@code axiz.config.required} is missing or blank. The
 * message lists property names only, never values.
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
