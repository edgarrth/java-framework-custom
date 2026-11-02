package pe.axiz.core.secrets;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.commons.Stages;

/** Ordered native sources. Failures propagate; later sources are queried only after absence. */
public final class CompositeAsyncSecretResolver implements AsyncSecretResolver {
  private final List<AsyncSecretResolver> delegates;

  public CompositeAsyncSecretResolver(List<AsyncSecretResolver> delegates) {
    this.delegates = List.copyOf(delegates);
  }

  @Override
  public CompletionStage<Optional<SecretValue>> resolve(String name) {
    CompletionStage<Optional<SecretValue>> result =
        CompletableFuture.completedFuture(Optional.empty());
    for (var delegate : delegates) {
      result =
          Stages.compose(
              result,
              existing ->
                  existing.isPresent()
                      ? CompletableFuture.completedFuture(existing)
                      : Stages.call(() -> delegate.resolve(name)));
    }
    return result;
  }
}
