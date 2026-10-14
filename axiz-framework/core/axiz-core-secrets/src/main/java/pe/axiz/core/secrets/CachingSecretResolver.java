package pe.axiz.core.secrets;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches resolved secrets for a TTL to avoid calling the secret store on every use while still
 * picking up rotations. Missing secrets are not cached.
 */
public final class CachingSecretResolver implements SecretResolver {

  private final SecretResolver delegate;
  private final Duration ttl;
  private final Clock clock;
  private final Map<String, Entry> cache = new ConcurrentHashMap<>();

  public CachingSecretResolver(SecretResolver delegate, Duration ttl, Clock clock) {
    this.delegate = Objects.requireNonNull(delegate);
    this.ttl = Objects.requireNonNull(ttl);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public Optional<SecretValue> resolve(String name) {
    Instant now = clock.instant();
    Entry e = cache.get(name);
    if (e != null && now.isBefore(e.expiresAt)) {
      return Optional.of(e.value);
    }
    Optional<SecretValue> v = delegate.resolve(name);
    v.ifPresentOrElse(
        value -> cache.put(name, new Entry(value, now.plus(ttl))), () -> cache.remove(name));
    return v;
  }

  public void evict(String name) {
    cache.remove(name);
  }

  private record Entry(SecretValue value, Instant expiresAt) {}
}
