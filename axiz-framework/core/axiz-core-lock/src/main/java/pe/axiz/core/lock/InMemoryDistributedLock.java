package pe.axiz.core.lock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Lock for a single process (tests, local runs). */
public final class InMemoryDistributedLock implements DistributedLock {

  private final Map<String, Owner> owners = new ConcurrentHashMap<>();
  private final Clock clock;

  public InMemoryDistributedLock(Clock clock) {
    this.clock = clock;
  }

  @Override
  public Optional<LockHandle> tryAcquire(String resource, Duration lease) {
    String token = UUID.randomUUID().toString();
    Instant now = clock.instant();
    Owner mine = new Owner(token, now.plus(lease));
    Owner result =
        owners.compute(
            resource,
            (k, current) -> current == null || !now.isBefore(current.expiresAt) ? mine : current);
    if (!result.token().equals(token)) {
      return Optional.empty();
    }
    return Optional.of(new Handle(resource, token));
  }

  private record Owner(String token, Instant expiresAt) {}

  private final class Handle implements LockHandle {
    private final String resource;
    private final String token;

    Handle(String resource, String token) {
      this.resource = resource;
      this.token = token;
    }

    @Override
    public String resource() {
      return resource;
    }

    @Override
    public String token() {
      return token;
    }

    @Override
    public boolean renew(Duration lease) {
      Owner renewed =
          owners.computeIfPresent(
              resource,
              (k, o) -> {
                if (!o.token.equals(token)) return o;
                Instant now = clock.instant();
                return now.isBefore(o.expiresAt) ? new Owner(token, now.plus(lease)) : null;
              });
      return renewed != null && renewed.token.equals(token);
    }

    @Override
    public void release() {
      owners.computeIfPresent(resource, (k, o) -> o.token.equals(token) ? null : o);
    }
  }
}
