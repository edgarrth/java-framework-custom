package pe.axiz.adapter.idempotency.redis;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.commons.Stages;
import pe.axiz.core.idempotency.AsyncIdempotencyStore;
import pe.axiz.core.idempotency.IdempotencyRecord;
import pe.axiz.core.idempotency.StoredResponse;
import pe.axiz.core.idempotency.StoredResponseCodec;

/**
 * Native asynchronous Redis store, sharing the atomic fencing scripts with the synchronous store.
 */
public final class RedisAsyncIdempotencyStore implements AsyncIdempotencyStore {
  private final RedisAsyncCommands<String, String> redis;
  private final String prefix;
  private final Clock clock;

  public RedisAsyncIdempotencyStore(
      RedisAsyncCommands<String, String> redis, String prefix, Clock clock) {
    this.redis = Objects.requireNonNull(redis);
    this.prefix = Objects.requireNonNull(prefix);
    this.clock = Objects.requireNonNull(clock);
  }

  private long ttl(Instant expiresAt) {
    return Math.max(1, Duration.between(clock.instant(), expiresAt).toMillis());
  }

  @Override
  public CompletionStage<Boolean> tryStart(IdempotencyRecord record) {
    return Stages.map(
        redis.set(
            prefix + record.key(),
            StoredResponseCodec.write(record),
            SetArgs.Builder.nx().px(ttl(record.expiresAt()))),
        "OK"::equals);
  }

  @Override
  public CompletionStage<Optional<IdempotencyRecord>> find(String key, Instant now) {
    return Stages.map(
        redis.get(prefix + key),
        value ->
            value == null ? Optional.empty() : Optional.of(StoredResponseCodec.read(key, value)));
  }

  @Override
  public CompletionStage<Boolean> complete(
      String key, String owner, StoredResponse response, Instant expiresAt) {
    return Stages.compose(
        find(key, clock.instant()),
        current -> {
          if (current.isEmpty() || !current.get().heldBy(owner))
            return CompletableFuture.completedFuture(false);
          IdempotencyRecord done =
              new IdempotencyRecord(
                  key,
                  current.get().fingerprint(),
                  IdempotencyRecord.Status.COMPLETED,
                  owner,
                  response,
                  expiresAt);
          CompletionStage<Long> changed =
              redis.eval(
                  RedisIdempotencyStore.COMPLETE_SCRIPT,
                  ScriptOutputType.INTEGER,
                  new String[] {prefix + key},
                  owner,
                  StoredResponseCodec.write(done),
                  Long.toString(ttl(expiresAt)));
          return Stages.map(changed, value -> value != null && value == 1L);
        });
  }

  @Override
  public CompletionStage<Boolean> release(String key, String owner) {
    CompletionStage<Long> released =
        redis.eval(
            RedisIdempotencyStore.RELEASE_SCRIPT,
            ScriptOutputType.INTEGER,
            new String[] {prefix + key},
            owner);
    return Stages.map(released, value -> value != null && value == 1L);
  }
}
