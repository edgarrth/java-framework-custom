package pe.axiz.core.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Non-blocking byte cache tier. Completion means the store acknowledged the operation. */
public interface AsyncByteCache {
  CompletionStage<Optional<byte[]>> get(String key);

  CompletionStage<Optional<CachedValue>> getWithTtl(String key);

  CompletionStage<Void> put(String key, byte[] value, Duration ttl);

  CompletionStage<Void> evict(String key);

  CompletionStage<Long> evictByPrefix(String prefix);
}
