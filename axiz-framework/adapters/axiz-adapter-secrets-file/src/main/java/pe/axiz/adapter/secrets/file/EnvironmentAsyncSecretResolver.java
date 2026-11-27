package pe.axiz.adapter.secrets.file;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.secrets.AsyncSecretResolver;
import pe.axiz.core.secrets.SecretValue;

/**
 * Instantánea del entorno solo de CPU, con el mismo mapeo de nombres lógicos que la fuente
 * síncrona.
 */
public final class EnvironmentAsyncSecretResolver implements AsyncSecretResolver {
  private final Map<String, String> environment;

  public EnvironmentAsyncSecretResolver(Map<String, String> environment) {
    this.environment = Map.copyOf(environment);
  }

  public static EnvironmentAsyncSecretResolver system() {
    return new EnvironmentAsyncSecretResolver(System.getenv());
  }

  @Override
  public CompletionStage<Optional<SecretValue>> resolve(String name) {
    String value = environment.get(EnvironmentSecretResolver.variableName(name));
    return CompletableFuture.completedFuture(
        value == null || value.isEmpty() ? Optional.empty() : Optional.of(SecretValue.of(value)));
  }
}
