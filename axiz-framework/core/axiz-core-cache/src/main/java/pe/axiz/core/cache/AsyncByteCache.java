package pe.axiz.core.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Nivel de caché de bytes no bloqueante. La finalización significa que el almacén confirmó la
 * operación.
 */
public interface AsyncByteCache {
  CompletionStage<Optional<byte[]>> get(String key);

  CompletionStage<Optional<CachedValue>> getWithTtl(String key);

  CompletionStage<Void> put(String key, byte[] value, Duration ttl);

  CompletionStage<Void> evict(String key);

  CompletionStage<Long> evictByPrefix(String prefix);
}
