package pe.axiz.core.idempotency;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Native asynchronous reconciliation; implementations must not block the caller. */
@FunctionalInterface
public interface AsyncIdempotencyResolver {
  CompletionStage<Optional<Resolution>> resolve(String key, IdempotencyRecord record);
}
