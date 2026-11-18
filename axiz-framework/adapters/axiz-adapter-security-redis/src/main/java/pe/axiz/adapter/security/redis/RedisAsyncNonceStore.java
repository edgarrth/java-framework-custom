package pe.axiz.adapter.security.redis;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.security.AsyncNonceStore;

/** Atomic native asynchronous nonce acquisition. Any store failure propagates to authentication. */
public final class RedisAsyncNonceStore implements AsyncNonceStore {
  private final RedisAsyncCommands<String, String> redis;
  private final String prefix;

  public RedisAsyncNonceStore(RedisAsyncCommands<String, String> redis, String prefix) {
    this.redis = Objects.requireNonNull(redis);
    this.prefix = Objects.requireNonNull(prefix);
  }

  @Override
  public CompletionStage<Boolean> markUsed(String nonce, Duration ttl) {
    return Stages.map(
        redis.set(prefix + nonce, "1", SetArgs.Builder.nx().px(ttl.toMillis())), "OK"::equals);
  }
}
