package pe.axiz.adapter.security.redis;

import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.commons.Stages;
import pe.axiz.core.security.AsyncRateLimiter;
import pe.axiz.core.security.RateLimitUnavailableException;
import pe.axiz.core.security.RateLimiter;

/** Native asynchronous fixed-window rate limits, with explicit per-route store failure policy. */
public final class RedisAsyncRateLimiter implements AsyncRateLimiter {
  private final RedisAsyncCommands<String, String> redis;
  private final String prefix;

  public RedisAsyncRateLimiter(RedisAsyncCommands<String, String> redis, String prefix) {
    this.redis = Objects.requireNonNull(redis);
    this.prefix = Objects.requireNonNull(prefix);
  }

  @Override
  public CompletionStage<RateLimiter.Decision> tryAcquire(
      String key, long limit, Duration window, boolean failOpen) {
    CompletionStage<List<Long>> reply =
        Stages.call(
            () ->
                redis.eval(
                    RedisRateLimiter.SCRIPT,
                    ScriptOutputType.MULTI,
                    new String[] {prefix + key},
                    Long.toString(window.toMillis())));
    return Stages.recover(
        Stages.map(
            reply,
            result -> {
              long count = result.getFirst();
              long ttl = result.get(1);
              return count <= limit
                  ? new RateLimiter.Decision(true, limit - count, Duration.ZERO)
                  : new RateLimiter.Decision(false, 0, Duration.ofMillis(Math.max(ttl, 1)));
            }),
        failure -> {
          if (!(failure instanceof RedisException)) throw new CompletionException(failure);
          if (failOpen) return new RateLimiter.Decision(true, -1, Duration.ZERO);
          throw new RateLimitUnavailableException(failure);
        });
  }
}
