package pe.axiz.core.lock.redis;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import pe.axiz.core.lock.DistributedLock;
import pe.axiz.core.lock.LockHandle;

/**
 * Lock on Redis: {@code SET key token NX PX lease}; renew and release are Lua scripts that act only
 * when the stored token is ours, so an expired owner never touches the next owner's lock.
 */
public final class RedisDistributedLock implements DistributedLock {

  static final String RELEASE =
      "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return"
          + " 0 end";
  static final String RENEW =
      "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('PEXPIRE', KEYS[1], ARGV[2])"
          + " else return 0 end";

  private final RedisCommands<String, String> redis;
  private final String prefix;

  public RedisDistributedLock(RedisCommands<String, String> redis, String prefix) {
    this.redis = Objects.requireNonNull(redis);
    this.prefix = Objects.requireNonNull(prefix);
  }

  @Override
  public Optional<LockHandle> tryAcquire(String resource, Duration lease) {
    String token = UUID.randomUUID().toString();
    String key = prefix + resource;
    String ok = redis.set(key, token, SetArgs.Builder.nx().px(lease.toMillis()));
    if (!"OK".equals(ok)) {
      return Optional.empty();
    }
    return Optional.of(
        new LockHandle() {
          @Override
          public String resource() {
            return resource;
          }

          @Override
          public String token() {
            return token;
          }

          @Override
          public boolean renew(Duration newLease) {
            Long r =
                redis.eval(
                    RENEW,
                    ScriptOutputType.INTEGER,
                    new String[] {key},
                    token,
                    Long.toString(newLease.toMillis()));
            return r != null && r == 1L;
          }

          @Override
          public void release() {
            redis.eval(RELEASE, ScriptOutputType.INTEGER, new String[] {key}, token);
          }
        });
  }
}
