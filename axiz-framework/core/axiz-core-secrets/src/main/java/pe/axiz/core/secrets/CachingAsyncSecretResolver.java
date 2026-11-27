package pe.axiz.core.secrets;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.concurrent.AsyncLoadingCache;

/**
 * Caché con TTL acotada, cargas nativas coalescidas y cancelación, sin guardar en caché los
 * secretos ausentes.
 */
public final class CachingAsyncSecretResolver implements AsyncSecretResolver, AutoCloseable {
  private final AsyncSecretResolver delegate;
  private final SecretCacheInstrumentation instrumentation;
  private final AsyncLoadingCache<Optional<SecretValue>> cache;

  public CachingAsyncSecretResolver(AsyncSecretResolver delegate, Duration ttl, Clock clock) {
    this(delegate, ttl, clock, SecretCacheObserver.NONE);
  }

  public CachingAsyncSecretResolver(
      AsyncSecretResolver delegate, Duration ttl, Clock clock, SecretCacheObserver observer) {
    instrumentation = new SecretCacheInstrumentation(observer);
    this.delegate = Objects.requireNonNull(delegate);
    cache =
        new AsyncLoadingCache<>(
            ttl,
            Long.MAX_VALUE,
            CachingSecretResolver.MAX_CACHED_SECRETS,
            clock,
            Optional::isPresent,
            CachingSecretResolver::snapshot,
            CachingSecretResolver::release);
  }

  @Override
  public CompletionStage<Optional<SecretValue>> resolve(String name) {
    return cache.get(
        name, () -> instrumentation.load(() -> delegate.resolve(name)), instrumentation::lookup);
  }

  public void evict(String name) {
    cache.evict(name);
  }

  @Override
  public void close() {
    cache.close();
  }
}
