package pe.axiz.spring.config.aws;

import java.util.Objects;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import pe.axiz.spring.config.ConfigurationEnvironment;

/**
 * Valida la configuración efectiva después de cargar las importaciones, antes de crear los beans.
 */
public final class AxizConfigAwsValidationEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {
  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
    String environment =
        ConfigurationEnvironment.requireEnvironment(
            ConfigurationEnvironment.normalize(env.getProperty("axiz.config.environment")));
    var captured = env.getPropertySources().get(ParameterStoreBootstrapSelection.NAME);
    if (!(captured instanceof ParameterStoreBootstrapSelection selection))
      throw new IllegalStateException("Axiz configuration bootstrap selection is missing");
    var before = selection.getSource();
    if (before.environment() != null && !environment.equals(before.environment()))
      throw new IllegalStateException(
          "axiz.config.environment differs from the bootstrap selection");
    boolean enabled = env.getProperty("axiz.config.parameter-store.enabled", Boolean.class, false);
    if (enabled != before.enabled())
      throw new IllegalStateException(
          "axiz.config.parameter-store.enabled must be selected in process configuration before"
              + " Config Data");
    if (enabled
        && (!Objects.equals(
                before.application(), env.getProperty("axiz.config.parameter-store.application"))
            || !Objects.equals(
                before.prefix(), env.getProperty("axiz.config.parameter-store.prefix", "/axiz"))))
      throw new IllegalStateException(
          "Parameter Store selectors differ from the bootstrap selection");
  }

  @Override
  public int getOrder() {
    return ConfigDataEnvironmentPostProcessor.ORDER + 2;
  }
}
