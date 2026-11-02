package pe.axiz.core.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Native asynchronous idempotency port; every mutation is atomically fenced by its owner token. */
public interface AsyncIdempotencyStore {
  CompletionStage<Boolean> tryStart(IdempotencyRecord record);

  CompletionStage<Optional<IdempotencyRecord>> find(String key, Instant now);

  CompletionStage<Boolean> complete(
      String key, String owner, StoredResponse response, Instant expiresAt);

  CompletionStage<Boolean> release(String key, String owner);
}
