package pe.axiz.core.idempotency;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.concurrent.Stages;

/** Provider-side bounded CAS protocol; clocks are trusted infrastructure, never request input. */
public final class AsyncIdempotencyCas {
  private AsyncIdempotencyCas() {}

  /**
   * Shared owner-fenced completion protocol for CAS stores. Providers supply their injected clock
   * so every retry rechecks retention; their compare-and-set remains the atomic boundary.
   */
  public static CompletionStage<Boolean> complete(
      AsyncIdempotencyStore store,
      String key,
      String owner,
      StoredResponse response,
      Instant expiresAt,
      Clock clock) {
    return mutate(
        store, key, owner, clock::instant, current -> current.completed(response, expiresAt), 0);
  }

  /** Refreshes time after contention; false means no confirmed mutation, including exhaustion. */
  public static CompletionStage<Boolean> renew(
      AsyncIdempotencyStore store,
      String key,
      String owner,
      Instant leaseUntil,
      Instant retainUntil,
      Clock clock) {
    return mutate(
        store,
        key,
        owner,
        clock::instant,
        current ->
            current.leaseUntil().isAfter(clock.instant())
                ? current.renewed(leaseUntil, retainUntil)
                : null,
        0);
  }

  /** Retention and ownership are reevaluated on every retry; no expired record is resurrected. */
  public static CompletionStage<Boolean> markUnknown(
      AsyncIdempotencyStore store, String key, String owner, String diagnostic, Clock clock) {
    return mutate(store, key, owner, clock::instant, current -> current.unknown(diagnostic), 0);
  }

  static CompletionStage<Boolean> mutate(
      AsyncIdempotencyStore store,
      String key,
      String owner,
      Supplier<Instant> now,
      Function<IdempotencyRecord, @Nullable IdempotencyRecord> update,
      int attempt) {
    if (attempt >= 16) return CompletableFuture.completedFuture(false);
    return Stages.compose(
        store.find(key, now.get()),
        found -> {
          if (found.isEmpty() || !found.get().heldBy(owner))
            return CompletableFuture.completedFuture(false);
          IdempotencyRecord current = found.get();
          IdempotencyRecord next = update.apply(current);
          if (next == null) return CompletableFuture.completedFuture(false);
          return Stages.compose(
              store.compareAndSet(current, next),
              changed ->
                  changed
                      ? CompletableFuture.completedFuture(true)
                      : mutate(store, key, owner, now, update, attempt + 1));
        });
  }
}
