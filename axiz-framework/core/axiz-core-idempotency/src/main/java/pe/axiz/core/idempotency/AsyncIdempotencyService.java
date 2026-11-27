package pe.axiz.core.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.errors.BusinessException;

/**
 * Idempotencia no bloqueante con las mismas claves con alcance, decisiones de replay, leases y
 * fencing.
 */
public final class AsyncIdempotencyService {
  private final AsyncIdempotencyStore store;
  private final Duration inProgressTtl;
  private final Duration retention;
  private final Clock clock;
  private final @Nullable AsyncIdempotencyResolver resolver;

  public AsyncIdempotencyService(
      AsyncIdempotencyStore store, Duration inProgressTtl, Duration retention, Clock clock) {
    this(store, inProgressTtl, retention, clock, null);
  }

  public AsyncIdempotencyService(
      AsyncIdempotencyStore store,
      Duration inProgressTtl,
      Duration retention,
      Clock clock,
      @Nullable AsyncIdempotencyResolver resolver) {
    this.store = Objects.requireNonNull(store);
    this.inProgressTtl = Objects.requireNonNull(inProgressTtl);
    this.retention = Objects.requireNonNull(retention);
    this.clock = Objects.requireNonNull(clock);
    this.resolver = resolver;
    if (inProgressTtl.compareTo(Duration.ofMillis(3)) < 0)
      throw new IllegalArgumentException("inProgressTtl must be at least 3 milliseconds");
    if (retention.compareTo(inProgressTtl) <= 0)
      throw new IllegalArgumentException("retention must exceed the execution lease");
  }

  public CompletionStage<IdempotencyService.Decision> begin(
      String scope, @Nullable String idempotencyKey, String fingerprint) {
    String key = IdempotencyService.scopedKey(scope, idempotencyKey);
    Instant now = clock.instant();
    return Stages.compose(
        tryStart(key, fingerprint, now),
        started -> {
          if (started.isPresent()) return CompletableFuture.completedFuture(started.get());
          return Stages.compose(
              store.find(key, now),
              existing -> {
                IdempotencyRecord record =
                    existing.orElseThrow(
                        () ->
                            new BusinessException(
                                IdempotencyErrors.OUTCOME_UNKNOWN,
                                "operation changed during acquisition"));
                IdempotencyService.requireFingerprint(record, fingerprint);
                AsyncIdempotencyResolver reconciliation = resolver;
                if (IdempotencyService.uncertain(record, now) && reconciliation != null) {
                  return Stages.compose(
                      reconciliation.resolve(key, record),
                      resolution -> {
                        if (resolution.isEmpty())
                          return CompletableFuture.completedFuture(
                              IdempotencyService.existingDecision(record, fingerprint, now));
                        return Stages.compose(
                            store.resolve(record, resolution.get(), now, now.plus(retention)),
                            changed -> {
                              if (changed && resolution.get().response() == null)
                                throw new BusinessException(
                                    IdempotencyErrors.RESOLVED_WITHOUT_EFFECT,
                                    "operation reconciled without effect; repeat the request with"
                                        + " the same key");
                              return Stages.map(
                                  store.find(key, now),
                                  current ->
                                      IdempotencyService.existingDecision(
                                          current.orElseThrow(
                                              () ->
                                                  new BusinessException(
                                                      IdempotencyErrors.OUTCOME_UNKNOWN,
                                                      "concurrent reconciliation")),
                                          fingerprint,
                                          now));
                            });
                      });
                }
                return CompletableFuture.completedFuture(
                    IdempotencyService.existingDecision(record, fingerprint, now));
              });
        });
  }

  private CompletionStage<Optional<IdempotencyService.Proceed>> tryStart(
      String key, String fingerprint, Instant now) {
    String owner = UUID.randomUUID().toString();
    return Stages.map(
        store.tryStart(
            IdempotencyRecord.builder(
                    key, fingerprint, now.plus(inProgressTtl), now.plus(retention))
                .status(IdempotencyRecord.Status.IN_PROGRESS)
                .owner(owner)
                .response(null)
                .resolutionVersion(0)
                .failure(null)
                .build()),
        started ->
            started ? Optional.of(new IdempotencyService.Proceed(key, owner)) : Optional.empty());
  }

  public CompletionStage<Boolean> complete(
      IdempotencyService.Proceed execution, StoredResponse response) {
    return store.complete(
        execution.key(), execution.owner(), response, clock.instant().plus(retention));
  }

  public CompletionStage<Boolean> fail(IdempotencyService.Proceed execution) {
    return markUnknown(execution, "execution-failed");
  }

  public CompletionStage<Boolean> markUnknown(
      IdempotencyService.Proceed execution, String diagnostic) {
    return store.markUnknown(execution.key(), execution.owner(), diagnostic, clock.instant());
  }

  public CompletionStage<Boolean> releaseWithoutEffect(IdempotencyService.Proceed execution) {
    return store.releaseWithoutEffect(execution.key(), execution.owner());
  }

  public CompletionStage<Boolean> renew(IdempotencyService.Proceed execution) {
    Instant now = clock.instant();
    Instant leaseUntil = now.plus(inProgressTtl);
    return store.renew(
        execution.key(), execution.owner(), leaseUntil, leaseUntil.plus(retention), now);
  }

  public IdempotencyLease startLease(IdempotencyService.Proceed execution) {
    return IdempotencyLease.start(
        inProgressTtl.dividedBy(3),
        () -> renew(execution),
        () -> markUnknown(execution, "lease-lost"));
  }
}
