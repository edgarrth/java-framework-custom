package pe.axiz.core.secrets;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import pe.axiz.core.concurrent.AsyncLoadingCache;
import pe.axiz.core.concurrent.Stages;

/**
 * Synchronous secret cache. Shares bounded TTL/coalescing/eviction bookkeeping with the native
 * variant; its delegate executes on the synchronous caller, outside the cache bookkeeping lock.
 */
public final class CachingSecretResolver implements SecretResolver, AutoCloseable {
  static final int MAX_CACHED_SECRETS = 1000;
  private final SecretResolver delegate;
  private final SecretCacheInstrumentation instrumentation;
  private final AsyncLoadingCache<Optional<SecretValue>> cache;

  public CachingSecretResolver(SecretResolver delegate, Duration ttl, Clock clock) {
    this(delegate, ttl, clock, SecretCacheObserver.NONE);
  }

  public CachingSecretResolver(
      SecretResolver delegate, Duration ttl, Clock clock, SecretCacheObserver observer) {
    instrumentation = new SecretCacheInstrumentation(observer);
    this.delegate = Objects.requireNonNull(delegate);
    cache =
        new AsyncLoadingCache<>(
            ttl,
            Long.MAX_VALUE,
            MAX_CACHED_SECRETS,
            clock,
            Optional::isPresent,
            CachingSecretResolver::snapshot,
            CachingSecretResolver::release);
  }

  @Override
  public Optional<SecretValue> resolve(String name) {
    try {
      return cache
          .get(
              name,
              () ->
                  instrumentation.load(
                      () -> CompletableFuture.completedFuture(delegate.resolve(name))),
              instrumentation::lookup)
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

  static Optional<SecretValue> snapshot(Optional<SecretValue> values) {
    return values.map(value -> value.use(SecretValue::of));
  }

  static void release(Optional<SecretValue> values) {
    values.ifPresent(SecretValue::close);
  }

  @Override
  public void close() {
    cache.close();
  }
}
