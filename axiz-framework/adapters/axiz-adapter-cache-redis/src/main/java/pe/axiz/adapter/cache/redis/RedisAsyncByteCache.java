package pe.axiz.adapter.cache.redis;

import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.cache.AsyncByteCache;
import pe.axiz.core.cache.CachedValue;
import pe.axiz.core.commons.Stages;

/**
 * Redis/Valkey asynchronous cache; TTL reads are atomic and eviction uses paginated SCAN/UNLINK.
 */
public final class RedisAsyncByteCache implements AsyncByteCache {
  private final RedisAsyncCommands<String, byte[]> redis;

  public RedisAsyncByteCache(RedisAsyncCommands<String, byte[]> redis) {
    this.redis = Objects.requireNonNull(redis);
  }

  @Override
  public CompletionStage<Optional<byte[]>> get(String key) {
    return Stages.map(redis.get(key), Optional::ofNullable);
  }

  @Override
  public CompletionStage<Optional<CachedValue>> getWithTtl(String key) {
    CompletionStage<List<Object>> reply =
        redis.eval(RedisByteCache.GET_WITH_TTL, ScriptOutputType.MULTI, key);
    return Stages.map(
        reply,
        values -> {
          if (values == null || values.isEmpty() || !(values.getFirst() instanceof byte[] value))
            return Optional.empty();
          if (values.size() < 2 || !(values.get(1) instanceof Long millis))
            return Optional.of(CachedValue.unknownExpiry(value));
          if (millis == -2) return Optional.empty();
          return Optional.of(
              millis == -1
                  ? CachedValue.withoutExpiry(value)
                  : CachedValue.expiringIn(value, Duration.ofMillis(Math.max(0, millis))));
        });
  }

  @Override
  public CompletionStage<Void> put(String key, byte[] value, Duration ttl) {
    return Stages.ignore(redis.set(key, value, SetArgs.Builder.px(Math.max(1, ttl.toMillis()))));
  }

  @Override
  public CompletionStage<Void> evict(String key) {
    return Stages.ignore(redis.del(key));
  }

  @Override
  public CompletionStage<Long> evictByPrefix(String prefix) {
    return scan(
        ScanCursor.INITIAL,
        ScanArgs.Builder.matches(RedisByteCache.escapeGlob(prefix) + "*").limit(500),
        0L);
  }

  private CompletionStage<Long> scan(ScanCursor cursor, ScanArgs args, long count) {
    return Stages.compose(
        redis.scan(cursor, args),
        page -> {
          CompletionStage<Long> removed =
              page.getKeys().isEmpty()
                  ? CompletableFuture.completedFuture(0L)
                  : redis.unlink(page.getKeys().toArray(String[]::new));
          return Stages.compose(
              removed,
              deleted ->
                  page.isFinished()
                      ? CompletableFuture.completedFuture(count + deleted)
                      : scan(page, args, count + deleted));
        });
  }
}
