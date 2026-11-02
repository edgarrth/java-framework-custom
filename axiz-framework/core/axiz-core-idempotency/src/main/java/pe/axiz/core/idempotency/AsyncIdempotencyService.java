package pe.axiz.core.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.Stages;
import pe.axiz.core.errors.BusinessException;

/** Non-blocking idempotency with the same scoped keys, replay decisions, leases and fencing. */
public final class AsyncIdempotencyService {
  private final AsyncIdempotencyStore store;
  private final Duration inProgressTtl;
  private final Duration retention;
  private final Clock clock;

  public AsyncIdempotencyService(
      AsyncIdempotencyStore store, Duration inProgressTtl, Duration retention, Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.inProgressTtl = Objects.requireNonNull(inProgressTtl);
    this.retention = Objects.requireNonNull(retention);
    this.clock = Objects.requireNonNull(clock);
    if (!inProgressTtl.isPositive())
      throw new IllegalArgumentException("inProgressTtl must be positive");
  }

  public CompletionStage<IdempotencyService.Decision> begin(
      String scope, @Nullable String idempotencyKey, String fingerprint) {
    String key = IdempotencyService.scopedKey(scope, idempotencyKey);
    Instant now = clock.instant();
    return Stages.compose(
        tryStart(key, fingerprint, now),
        started -> {
          if (started.isPresent())
            return java.util.concurrent.CompletableFuture.completedFuture(started.get());
          return Stages.compose(
              store.find(key, now),
              existing -> {
                if (existing.isPresent())
                  return java.util.concurrent.CompletableFuture.completedFuture(
                      IdempotencyService.existingDecision(existing.get(), fingerprint));
                return Stages.map(
                    tryStart(key, fingerprint, now),
                    retry ->
                        retry
                            .<IdempotencyService.Decision>map(value -> value)
                            .orElseThrow(
                                () ->
                                    new BusinessException(
                                        IdempotencyErrors.IN_PROGRESS, "concurrent request")));
              });
        });
  }

  private CompletionStage<Optional<IdempotencyService.Proceed>> tryStart(
      String key, String fingerprint, Instant now) {
    String owner = UUID.randomUUID().toString();
    return Stages.map(
        store.tryStart(
            new IdempotencyRecord(
                key,
                fingerprint,
                IdempotencyRecord.Status.IN_PROGRESS,
                owner,
                null,
                now.plus(inProgressTtl))),
        started ->
            started ? Optional.of(new IdempotencyService.Proceed(key, owner)) : Optional.empty());
  }

  public CompletionStage<Boolean> complete(
      IdempotencyService.Proceed execution, StoredResponse response) {
    return store.complete(
        execution.key(), execution.owner(), response, clock.instant().plus(retention));
  }

  public CompletionStage<Boolean> fail(IdempotencyService.Proceed execution) {
    return store.release(execution.key(), execution.owner());
  }
}
