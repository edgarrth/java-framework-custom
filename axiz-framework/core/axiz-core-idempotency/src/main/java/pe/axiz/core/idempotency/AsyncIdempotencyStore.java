package pe.axiz.core.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.Stages;

/** Native asynchronous idempotency port; every mutation is atomically fenced by its owner token. */
public interface AsyncIdempotencyStore {
  CompletionStage<Boolean> compareAndSet(
      IdempotencyRecord expected, @Nullable IdempotencyRecord replacement);

  default CompletionStage<Boolean> renew(
      String key, String owner, Instant leaseUntil, Instant retainUntil, Instant now) {
    return mutate(
        key,
        owner,
        now,
        current ->
            current.leaseUntil().isAfter(now) ? current.renewed(leaseUntil, retainUntil) : null,
        0);
  }

  default CompletionStage<Boolean> markUnknown(
      String key, String owner, String diagnostic, Instant now) {
    return mutate(key, owner, now, current -> current.unknown(diagnostic), 0);
  }

  private CompletionStage<Boolean> mutate(
      String key,
      String owner,
      Instant now,
      Function<IdempotencyRecord, @Nullable IdempotencyRecord> update,
      int attempt) {
    if (attempt >= 16) return CompletableFuture.completedFuture(false);
    return Stages.compose(
        find(key, now),
        found -> {
          if (found.isEmpty() || !found.get().heldBy(owner))
            return CompletableFuture.completedFuture(false);
          IdempotencyRecord current = found.get();
          IdempotencyRecord next = update.apply(current);
          if (next == null) return CompletableFuture.completedFuture(false);
          return Stages.compose(
              compareAndSet(current, next),
              changed ->
                  changed
                      ? CompletableFuture.completedFuture(true)
                      : mutate(key, owner, now, update, attempt + 1));
        });
  }

  default CompletionStage<Boolean> releaseWithoutEffect(String key, String owner) {
    return release(key, owner);
  }

  default CompletionStage<Boolean> resolve(
      IdempotencyRecord expected, Resolution resolution, Instant now, Instant retainUntil) {
    if (!expected.liveAt(now)
        || expected.status() == IdempotencyRecord.Status.COMPLETED
        || (expected.status() == IdempotencyRecord.Status.IN_PROGRESS
            && expected.leaseUntil().isAfter(now))) {
      return CompletableFuture.completedFuture(false);
    }
    StoredResponse response = resolution.response();
    return compareAndSet(
        expected, response == null ? null : expected.completed(response, retainUntil));
  }

  CompletionStage<Boolean> tryStart(IdempotencyRecord record);

  CompletionStage<Optional<IdempotencyRecord>> find(String key, Instant now);

  CompletionStage<Boolean> complete(
      String key, String owner, StoredResponse response, Instant expiresAt);

  CompletionStage<Boolean> release(String key, String owner);
}
