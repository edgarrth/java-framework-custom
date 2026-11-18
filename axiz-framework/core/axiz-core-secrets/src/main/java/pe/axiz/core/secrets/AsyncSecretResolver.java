package pe.axiz.core.secrets;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.concurrent.Stages;

/** Native asynchronous secret resolution. Only absence may fall through to another source. */
@FunctionalInterface
public interface AsyncSecretResolver {
  CompletionStage<Optional<SecretValue>> resolve(String name);

  @SuppressWarnings(
      "FutureReturnValueIgnored") // Conversion and error completion are handled below.
  default CompletionStage<SecretValue> require(String name) {
    var source = Stages.call(() -> resolve(name));
    var result =
        new CompletableFuture<SecretValue>() {
          @Override
          public boolean cancel(boolean interrupt) {
            boolean cancelled = super.cancel(interrupt);
            if (cancelled) source.toCompletableFuture().cancel(interrupt);
            return cancelled;
          }
        };
    // Pure ownership conversion needs no caller context. Cleanup must also run when capture fails
    // elsewhere or a native source cannot honour cancellation and produces a late secret.
    source.whenComplete(
        (value, failure) -> {
          if (failure != null) result.completeExceptionally(Stages.unwrap(failure));
          else if (value == null || value.isEmpty())
            result.completeExceptionally(new SecretNotFoundException(name));
          else {
            SecretValue secret = value.get();
            if (!result.complete(secret)) secret.close();
          }
        });
    return result;
  }
}
