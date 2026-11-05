package pe.axiz.adapter.idempotency.redis;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.idempotency.IdempotencyRecord;
import pe.axiz.core.idempotency.IdempotencyStore;
import pe.axiz.core.idempotency.StoredResponse;
import pe.axiz.core.idempotency.StoredResponseCodec;

/**
 * Idempotency store on Redis/Valkey: {@code SET NX PX} to start; completion and release run as Lua
 * scripts that check, in the same atomic step, that the record is still IN_PROGRESS and held by the
 * caller's owner token. The TTL is always set on the same key that is written.
 */
public final class RedisIdempotencyStore implements IdempotencyStore {

  /** KEYS[1] record; ARGV[1] owner, ARGV[2] new value, ARGV[3] ttl millis. */
  static final String COMPLETE_SCRIPT =
      """
      local v = redis.call('GET', KEYS[1])
      if not v then return 0 end
      local r = cjson.decode(v)
      if r['status'] ~= 'IN_PROGRESS' or r['owner'] ~= ARGV[1] then return 0 end
      redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
      return 1
      """;

  /** KEYS[1] record; ARGV[1] owner. */
  static final String RELEASE_SCRIPT =
      """
      local v = redis.call('GET', KEYS[1])
      if not v then return 0 end
      local r = cjson.decode(v)
      if r['status'] ~= 'IN_PROGRESS' or r['owner'] ~= ARGV[1] then return 0 end
      redis.call('DEL', KEYS[1])
      return 1
      """;

  static final String CAS_SCRIPT =
      """
      local value = redis.call('GET', KEYS[1])
      if not value or value ~= ARGV[1] then return 0 end
      if ARGV[2] == '' then redis.call('DEL', KEYS[1])
      else redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3]) end
      return 1
      """;

  private final RedisCommands<String, String> redis;
  private final String prefix;
  private final Clock clock;

  public RedisIdempotencyStore(RedisCommands<String, String> redis, String prefix) {
    this(redis, prefix, Clock.systemUTC());
  }

  public RedisIdempotencyStore(RedisCommands<String, String> redis, String prefix, Clock clock) {
    this.redis = Objects.requireNonNull(redis);
    this.prefix = Objects.requireNonNull(prefix);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public boolean compareAndSet(
      IdempotencyRecord expected, @Nullable IdempotencyRecord replacement) {
    if (replacement != null && !expected.key().equals(replacement.key()))
      throw new IllegalArgumentException("different record key");
    Long changed =
        redis.eval(
            CAS_SCRIPT,
            ScriptOutputType.INTEGER,
            new String[] {prefix + expected.key()},
            StoredResponseCodec.write(expected),
            replacement == null ? "" : StoredResponseCodec.write(replacement),
            Long.toString(replacement == null ? 1 : ttlMillis(replacement.retainUntil())));
    return changed != null && changed == 1L;
  }

  @Override
  public boolean tryStart(IdempotencyRecord record) {
    long ttl = ttlMillis(record.expiresAt());
    String result =
        redis.set(
            prefix + record.key(), StoredResponseCodec.write(record), SetArgs.Builder.nx().px(ttl));
    return "OK".equals(result);
  }

  @Override
  public Optional<IdempotencyRecord> find(String key, Instant now) {
    String value = redis.get(prefix + key);
    return value == null
        ? Optional.empty()
        : Optional.of(StoredResponseCodec.read(key, value)).filter(record -> record.liveAt(now));
  }

  @Override
  public boolean complete(String key, String owner, StoredResponse response, Instant expiresAt) {
    for (int attempt = 0; attempt < 16; attempt++) {
      Optional<IdempotencyRecord> current = find(key, clock.instant());
      if (current.isEmpty() || !current.get().heldBy(owner)) return false;
      if (compareAndSet(current.get(), current.get().completed(response, expiresAt))) return true;
    }
    return false;
  }

  @Override
  public boolean release(String key, String owner) {
    Long released =
        redis.eval(RELEASE_SCRIPT, ScriptOutputType.INTEGER, new String[] {prefix + key}, owner);
    return released != null && released == 1L;
  }

  private long ttlMillis(Instant expiresAt) {
    return Math.max(1, Duration.between(clock.instant(), expiresAt).toMillis());
  }
}
