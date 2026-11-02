package pe.axiz.core.secrets;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** CPU-only environment snapshot, with the same logical-name mapping as the synchronous source. */
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
