package pe.axiz.adapter.lock.redis;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.commons.Stages;
import pe.axiz.core.lock.AsyncDistributedLock;
import pe.axiz.core.lock.AsyncLockHandle;

/** Non-blocking Redis leases, with native asynchronous renew and release fenced by owner. */
public final class RedisAsyncDistributedLock implements AsyncDistributedLock {
  private final RedisAsyncCommands<String, String> redis;
  private final String prefix;

  public RedisAsyncDistributedLock(RedisAsyncCommands<String, String> redis, String prefix) {
    this.redis = Objects.requireNonNull(redis);
    this.prefix = Objects.requireNonNull(prefix);
  }

  @Override
  public CompletionStage<Optional<AsyncLockHandle>> tryAcquire(String resource, Duration lease) {
    if (lease.isZero() || lease.isNegative())
      throw new IllegalArgumentException("lease must be positive");
    String token = UUID.randomUUID().toString();
    String key = prefix + resource;
    return Stages.map(
        redis.set(key, token, SetArgs.Builder.nx().px(lease.toMillis())),
        ok -> {
          if (!"OK".equals(ok)) return Optional.empty();
          return Optional.of(
              new AsyncLockHandle() {
                @Override
                public String resource() {
                  return resource;
                }

                @Override
                public String token() {
                  return token;
                }

                @Override
                public CompletionStage<Boolean> renew(Duration nextLease) {
                  if (nextLease.isZero() || nextLease.isNegative())
                    throw new IllegalArgumentException("lease must be positive");
                  CompletionStage<Long> value =
                      redis.eval(
                          RedisDistributedLock.RENEW,
                          ScriptOutputType.INTEGER,
                          new String[] {key},
                          token,
                          Long.toString(nextLease.toMillis()));
                  return Stages.map(value, n -> n != null && n == 1L);
                }

                @Override
                public CompletionStage<Void> release() {
                  CompletionStage<Long> value =
                      redis.eval(
                          RedisDistributedLock.RELEASE,
                          ScriptOutputType.INTEGER,
                          new String[] {key},
                          token);
                  return Stages.ignore(value);
                }
              });
        });
  }
}
