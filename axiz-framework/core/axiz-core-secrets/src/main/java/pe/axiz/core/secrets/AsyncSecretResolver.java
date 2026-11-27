package pe.axiz.core.secrets;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.concurrent.Stages;

/** Resolución asíncrona nativa de secretos. Solo la ausencia puede pasar a otra fuente. */
@FunctionalInterface
public interface AsyncSecretResolver {
  CompletionStage<Optional<SecretValue>> resolve(String name);

  @SuppressWarnings(
      "FutureReturnValueIgnored") // La conversión y la finalización con error se manejan abajo.
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
    // La conversión pura de propiedad no necesita el contexto de quien llama. La limpieza también
    // debe ejecutarse cuando la captura falla en otro punto o cuando una fuente nativa no puede
    // respetar la cancelación y produce un secreto tardío.
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
