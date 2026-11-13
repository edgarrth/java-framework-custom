package pe.axiz.spring.config;

import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

/** Captures selectors before Config Data without exposing synthetic configuration properties. */
final class ConfigBootstrapSelection extends PropertySource<ConfigBootstrapSelection.Selection> {
  static final String NAME = "axizConfigBootstrapSelection";

  record Selection(
      @Nullable String environment, boolean enabled, String application, String prefix) {}

  ConfigBootstrapSelection(ConfigurableEnvironment environment) {
    super(NAME, capture(environment));
  }

  private static Selection capture(ConfigurableEnvironment env) {
    boolean enabled = env.getProperty("axiz.config.parameter-store.enabled", Boolean.class, false);
    String environment = normalize(env.getProperty("axiz.config.environment"));
    String application = env.getProperty("axiz.config.parameter-store.application", "");
    String prefix = env.getProperty("axiz.config.parameter-store.prefix", "/axiz");
    if (enabled) {
      requireEnvironment(environment);
      if (!application.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*"))
        throw new IllegalStateException(
            "axiz.config.parameter-store.application is required in process configuration before Config Data");
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

  static String requireEnvironment(@Nullable String environment) {
    if (environment == null || environment.isBlank())
      throw new IllegalStateException(
          "axiz.config.environment (AXIZ_CONFIG_ENVIRONMENT) is required; "
              + "use local, test or ci only where one process runs");
    if (!environment.matches("[a-z0-9][a-z0-9_-]*"))
      throw new IllegalStateException("Invalid axiz.config.environment");
    return environment;
  }

  static @Nullable String normalize(@Nullable String value) {
    return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
  }

  @Override
  public @Nullable Object getProperty(String name) {
    return null;
  }
}
