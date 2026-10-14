package pe.axiz.core.secrets;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves secrets from environment variables (populated by External Secrets in Kubernetes). The
 * name {@code payments/db-password} maps to {@code PAYMENTS_DB_PASSWORD}.
 */
public final class EnvironmentSecretResolver implements SecretResolver {

  private final Map<String, String> environment;

  public EnvironmentSecretResolver(Map<String, String> environment) {
    this.environment = Map.copyOf(Objects.requireNonNull(environment));
  }

  public static EnvironmentSecretResolver system() {
    return new EnvironmentSecretResolver(System.getenv());
  }

  @Override
  public Optional<SecretValue> resolve(String name) {
    String value = environment.get(variableName(name));
    return value == null || value.isEmpty() ? Optional.empty() : Optional.of(SecretValue.of(value));
  }

  static String variableName(String name) {
    return name.replaceAll("[^A-Za-z0-9]", "_").toUpperCase(Locale.ROOT);
  }
}
