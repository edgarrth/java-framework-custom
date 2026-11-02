package pe.axiz.core.secrets;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import pe.axiz.core.commons.AsyncLoadingCache;
import pe.axiz.core.commons.Stages;

/**
 * Synchronous secret cache. Shares bounded TTL/coalescing/eviction bookkeeping with the native
 * variant; its delegate executes on the synchronous caller, outside the cache bookkeeping lock.
 */
public final class CachingSecretResolver implements SecretResolver {
  static final int MAX_CACHED_SECRETS = 1000;
  private final SecretResolver delegate;
  private final AsyncLoadingCache<Optional<SecretValue>> cache;

  public CachingSecretResolver(SecretResolver delegate, Duration ttl, Clock clock) {
    this.delegate = Objects.requireNonNull(delegate);
    cache =
        new AsyncLoadingCache<>(
            ttl, Long.MAX_VALUE, MAX_CACHED_SECRETS, clock, Optional::isPresent);
  }

  @Override
  public Optional<SecretValue> resolve(String name) {
    try {
      return cache
          .get(name, () -> CompletableFuture.completedFuture(delegate.resolve(name)))
          .toCompletableFuture()
          .join();
    } catch (CompletionException failure) {
      Throwable cause = Stages.unwrap(failure);
      if (cause instanceof RuntimeException runtime) throw runtime;
      if (cause instanceof Error error) throw error;
      throw failure;
    }
  }

  public void evict(String name) {
    cache.evict(name);
  }
}
