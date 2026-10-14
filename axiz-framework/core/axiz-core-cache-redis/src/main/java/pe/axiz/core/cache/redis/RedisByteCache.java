package pe.axiz.core.cache.redis;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import pe.axiz.core.cache.ByteCache;
import pe.axiz.core.cache.CacheUnavailableException;

/**
 * Redis (ElastiCache / Valkey) cache tier. {@code SET key value PX ttl} sets the TTL atomically on
 * the key that is written; prefix eviction uses {@code SCAN} in batches (never {@code KEYS}).
 */
public final class RedisByteCache implements ByteCache {

  private static final int SCAN_COUNT = 500;

  private final RedisCommands<String, byte[]> redis;

  public RedisByteCache(RedisCommands<String, byte[]> redis) {
    this.redis = Objects.requireNonNull(redis);
  }

  @Override
  public Optional<byte[]> get(String key) {
    try {
      return Optional.ofNullable(redis.get(key));
    } catch (RedisException e) {
      throw new CacheUnavailableException("Redis GET failed", e);
    }
  }

  @Override
  public void put(String key, byte[] value, Duration ttl) {
    try {
      redis.set(key, value, SetArgs.Builder.px(Math.max(1, ttl.toMillis())));
    } catch (RedisException e) {
      throw new CacheUnavailableException("Redis SET failed", e);
    }
  }

  @Override
  public void evict(String key) {
    try {
      redis.del(key);
    } catch (RedisException e) {
      throw new CacheUnavailableException("Redis DEL failed", e);
    }
  }

  @Override
  public long evictByPrefix(String prefix) {
    try {
      long deleted = 0;
      ScanCursor cursor = ScanCursor.INITIAL;
      ScanArgs args = ScanArgs.Builder.matches(escapeGlob(prefix) + "*").limit(SCAN_COUNT);
      do {
        KeyScanCursor<String> page = redis.scan(cursor, args);
        List<String> keys = page.getKeys();
        if (!keys.isEmpty()) {
          deleted += redis.unlink(keys.toArray(String[]::new));
        }
        cursor = page;
      } while (!cursor.isFinished());
      return deleted;
    } catch (RedisException e) {
      throw new CacheUnavailableException("Redis SCAN failed", e);
    }
  }

  static String escapeGlob(String prefix) {
    return prefix.replaceAll("([*?\\[\\]\\\\])", "\\\\$1");
  }
}
