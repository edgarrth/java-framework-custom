package pe.axiz.adapter.security.redis;

import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import pe.axiz.core.security.RateLimiter;

/**
 * Distributed fixed-window rate limiter on Redis. One atomic Lua script increments the counter and
 * sets the expiration on the same key on its first use. If Redis is unavailable the fail-open
 * policy decides: the one of the call, or else the one given at construction.
 */
public final class RedisRateLimiter implements RateLimiter {

  static final String SCRIPT =
      "local c = redis.call('INCR', KEYS[1]) "
          + "if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
          + "local ttl = redis.call('PTTL', KEYS[1]) "
          + "if ttl < 0 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) ttl = tonumber(ARGV[1]) end "
          + "return {c, ttl}";

  private final RedisCommands<String, String> redis;
  private final String prefix;
  private final boolean failOpen;

  public RedisRateLimiter(RedisCommands<String, String> redis, String prefix, boolean failOpen) {
    this.redis = Objects.requireNonNull(redis);
    this.prefix = Objects.requireNonNull(prefix);
    this.failOpen = failOpen;
  }

  @Override
  public Decision tryAcquire(String key, long limit, Duration window) {
    return tryAcquire(key, limit, window, failOpen);
  }

  @Override
  public Decision tryAcquire(String key, long limit, Duration window, boolean failOpen) {
    try {
      List<Long> result =
          redis.eval(
              SCRIPT,
              ScriptOutputType.MULTI,
              new String[] {prefix + key},
              Long.toString(window.toMillis()));
      long count = result.get(0);
      long ttl = result.get(1);
      if (count <= limit) {
        return new Decision(true, limit - count, Duration.ZERO);
      }
      return new Decision(false, 0, Duration.ofMillis(Math.max(ttl, 1)));
    } catch (RedisException e) {
      return failOpen ? new Decision(true, -1, Duration.ZERO) : new Decision(false, 0, window);
    }
  }
}
