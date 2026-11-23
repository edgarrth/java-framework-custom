package pe.axiz.spring.cache;

import java.time.Duration;
import java.util.Optional;
import pe.axiz.core.cache.AsyncByteCache;
import pe.axiz.spring.foundation.ReactiveStages;
import reactor.core.publisher.Mono;

/**
 * Fachada programática de Mono sobre la E/S asíncrona nativa de la caché. Las claves incluyen el
 * ámbito elegido de invocante o tenant.
 */
public final class ReactiveCaches extends AsyncCacheSettings {
  public ReactiveCaches(String prefix, AsyncByteCache store, AxizCacheProperties properties) {
    super(prefix, store, properties);
  }

  public Mono<Optional<byte[]>> get(String key) {
    return ReactiveStages.from(() -> store().get(prefix() + ":" + key));
  }

  public Mono<Void> put(String key, byte[] value, Duration ttl) {
    return ReactiveStages.from(() -> store().put(prefix() + ":" + key, value, ttl));
  }

  public Mono<Void> evict(String key) {
    return ReactiveStages.from(() -> store().evict(prefix() + ":" + key));
  }

  public Mono<Long> evictByPrefix(String keyPrefix) {
    return ReactiveStages.from(() -> store().evictByPrefix(prefix() + ":" + keyPrefix));
  }
}
