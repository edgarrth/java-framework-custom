package pe.axiz.spring.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Valida la configuración efectiva después de cargar las importaciones, antes de crear los beans.
 */
public final class AxizConfigValidationEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {
  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
    String environment =
        ConfigurationEnvironment.requireEnvironment(
            ConfigurationEnvironment.normalize(env.getProperty("axiz.config.environment")));
    var captured = env.getPropertySources().get(ConfigBootstrapSelection.NAME);
    if (!(captured instanceof ConfigBootstrapSelection selection))
      throw new IllegalStateException("Axiz configuration bootstrap selection is missing");
    var before = selection.getSource();
    if (before.environment() != null && !environment.equals(before.environment()))
      throw new IllegalStateException(
          "axiz.config.environment differs from the bootstrap selection");
  }

  @Override
  public int getOrder() {
    return ConfigDataEnvironmentPostProcessor.ORDER + 1;
  }
}
