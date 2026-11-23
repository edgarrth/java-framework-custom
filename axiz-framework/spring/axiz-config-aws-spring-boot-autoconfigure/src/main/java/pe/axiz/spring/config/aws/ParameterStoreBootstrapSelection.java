package pe.axiz.spring.config.aws;

import org.jspecify.annotations.Nullable;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import pe.axiz.spring.config.ConfigurationEnvironment;

/**
 * Captura los selectores antes de Config Data sin exponer propiedades de configuración sintéticas.
 */
final class ParameterStoreBootstrapSelection
    extends PropertySource<ParameterStoreBootstrapSelection.Selection> {
  static final String NAME = "axizParameterStoreBootstrapSelection";

  record Selection(
      @Nullable String environment, boolean enabled, String application, String prefix) {}

  ParameterStoreBootstrapSelection(ConfigurableEnvironment environment) {
    super(NAME, capture(environment));
  }

  private static Selection capture(ConfigurableEnvironment env) {
    boolean enabled = env.getProperty("axiz.config.parameter-store.enabled", Boolean.class, false);
    String environment =
        ConfigurationEnvironment.normalize(env.getProperty("axiz.config.environment"));
    String application = env.getProperty("axiz.config.parameter-store.application", "");
    String prefix = env.getProperty("axiz.config.parameter-store.prefix", "/axiz");
    if (enabled) {
      ConfigurationEnvironment.requireEnvironment(environment);
      if (!application.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*"))
        throw new IllegalStateException(
            "axiz.config.parameter-store.application is required in process configuration before"
                + " Config Data");
      if (!validPrefix(prefix))
        throw new IllegalStateException("Invalid axiz.config.parameter-store.prefix");
    }
    return new Selection(environment, enabled, application, prefix);
  }

  private static boolean validPrefix(String value) {
    if (!value.startsWith("/") || value.length() < 2 || value.endsWith("/")) return false;
    char previous = '/';
    for (int i = 1; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '/') {
        if (previous == '/') return false;
      } else if (!((c >= 'a' && c <= 'z')
          || (c >= 'A' && c <= 'Z')
          || (c >= '0' && c <= '9')
          || c == '_'
          || c == '-')) return false;
      previous = c;
    }
    return true;
  }

  @Override
  public @Nullable Object getProperty(String name) {
    return null;
  }
}
