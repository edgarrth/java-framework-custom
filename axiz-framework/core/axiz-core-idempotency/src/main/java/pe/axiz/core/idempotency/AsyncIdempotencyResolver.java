package pe.axiz.core.idempotency;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Reconciliación asíncrona nativa; las implementaciones no deben bloquear a quien llama. */
@FunctionalInterface
public interface AsyncIdempotencyResolver {
  CompletionStage<Optional<Resolution>> resolve(String key, IdempotencyRecord record);
}
