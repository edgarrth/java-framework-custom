package pe.axiz.core.secrets;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.commons.AsyncLoadingCache;

/** Bounded TTL cache, coalesced native loads and cancellation without caching missing secrets. */
public final class CachingAsyncSecretResolver implements AsyncSecretResolver {
  private final AsyncSecretResolver delegate;
  private final AsyncLoadingCache<Optional<SecretValue>> cache;

  public CachingAsyncSecretResolver(AsyncSecretResolver delegate, Duration ttl, Clock clock) {
    this.delegate = Objects.requireNonNull(delegate);
    cache =
        new AsyncLoadingCache<>(
            ttl,
            Long.MAX_VALUE,
            CachingSecretResolver.MAX_CACHED_SECRETS,
            clock,
            Optional::isPresent);
  }

  @Override
  public CompletionStage<Optional<SecretValue>> resolve(String name) {
    return cache.get(name, () -> delegate.resolve(name));
  }

  public void evict(String name) {
    cache.evict(name);
  }
}
