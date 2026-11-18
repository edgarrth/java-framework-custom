package pe.axiz.spring.config;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Canonical logical environment used consistently by bootstrap integrations. */
public final class ConfigurationEnvironment {
  private ConfigurationEnvironment() {}

  public static String requireEnvironment(@Nullable String environment) {
    if (environment == null || environment.isBlank())
      throw new IllegalStateException(
          "axiz.config.environment (AXIZ_CONFIG_ENVIRONMENT) is required; "
              + "use local, test or ci only where one process runs");
    if (!environment.matches("[a-z0-9][a-z0-9_-]*"))
      throw new IllegalStateException("Invalid axiz.config.environment");
    return environment;
  }

  public static @Nullable String normalize(@Nullable String value) {
    return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
  }
}
