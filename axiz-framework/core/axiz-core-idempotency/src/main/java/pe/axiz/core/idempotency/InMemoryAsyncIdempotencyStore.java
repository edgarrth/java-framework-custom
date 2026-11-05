package pe.axiz.core.idempotency;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;

/**
 * Single-process CPU-only store. Shared environments must use a native remote asynchronous store.
 */
public final class InMemoryAsyncIdempotencyStore implements AsyncIdempotencyStore {
  private final InMemoryIdempotencyStore records;

  public InMemoryAsyncIdempotencyStore(Clock clock) {
    records = new InMemoryIdempotencyStore(clock);
  }

  @Override
  public CompletionStage<Boolean> compareAndSet(
      IdempotencyRecord expected, @Nullable IdempotencyRecord replacement) {
    return CompletableFuture.completedFuture(records.compareAndSet(expected, replacement));
  }

  @Override
  public CompletionStage<Boolean> tryStart(IdempotencyRecord record) {
    return CompletableFuture.completedFuture(records.tryStart(record));
  }

  @Override
  public CompletionStage<Optional<IdempotencyRecord>> find(String key, Instant now) {
    return CompletableFuture.completedFuture(records.find(key, now));
  }

  @Override
  public CompletionStage<Boolean> complete(
      String key, String owner, StoredResponse response, Instant expiresAt) {
    return CompletableFuture.completedFuture(records.complete(key, owner, response, expiresAt));
  }

  @Override
  public CompletionStage<Boolean> release(String key, String owner) {
    return CompletableFuture.completedFuture(records.release(key, owner));
  }
}
