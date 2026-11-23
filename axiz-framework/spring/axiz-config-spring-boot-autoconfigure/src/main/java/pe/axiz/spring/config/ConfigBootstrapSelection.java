package pe.axiz.spring.config;

import org.jspecify.annotations.Nullable;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

/** Captura el entorno antes de Config Data sin propiedades de configuración sintéticas. */
final class ConfigBootstrapSelection extends PropertySource<ConfigBootstrapSelection.Selection> {
  static final String NAME = "axizConfigBootstrapSelection";

  record Selection(@Nullable String environment) {}

  ConfigBootstrapSelection(ConfigurableEnvironment env) {
    super(
        NAME,
        new Selection(
            ConfigurationEnvironment.normalize(env.getProperty("axiz.config.environment"))));
  }

  @Override
  public @Nullable Object getProperty(String name) {
    return null;
  }
}
