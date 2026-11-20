package pe.axiz.core.saga;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import pe.axiz.core.lock.LockHandle;

/** In-memory saga store for tests and local runs. State and ownership share one atomic boundary. */
public final class InMemorySagaStateStore implements SagaStateStore {
  private final Map<String, SagaState> states = new HashMap<>();
  private final Map<String, Owner> owners = new HashMap<>();
  private final Clock clock;
  private final Object monitor = new Object();

  public InMemorySagaStateStore() {
    this(Clock.systemUTC());
  }

  public InMemorySagaStateStore(Clock clock) {
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public void create(SagaState state) {
    synchronized (monitor) {
      if (states.putIfAbsent(state.sagaId(), state) != null)
        throw new IllegalStateException("Saga already exists");
    }
  }

  private void updateState(SagaState state) {
    SagaState current = states.get(state.sagaId());
    if (current == null || current.version() != state.version() - 1)
      throw new SagaConcurrencyException(state.sagaId());
    states.put(state.sagaId(), state);
  }

  @Override
  public void update(SagaState state, String owner) {
    synchronized (monitor) {
      if (!held(state.sagaId(), owner)) throw new SagaConcurrencyException(state.sagaId());
      updateState(state);
    }
  }

  @Override
  public Optional<SagaState> find(String sagaId) {
    synchronized (monitor) {
      return Optional.ofNullable(states.get(sagaId));
    }
  }

  @Override
  public List<SagaState> findStale(Instant updatedBefore, int max) {
    synchronized (monitor) {
      return states.values().stream()
          .filter(s -> !s.status().terminal() && s.updatedAt().isBefore(updatedBefore))
          .filter(
              s -> {
                Owner owner = owners.get(s.sagaId());
                return owner == null || !owner.until().isAfter(clock.instant());
              })
          .limit(max)
          .toList();
    }
  }

  @Override
  public Optional<LockHandle> tryAcquire(String resource, Duration lease) {
    synchronized (monitor) {
      if (lease.isNegative() || lease.isZero())
        throw new IllegalArgumentException("Positive lease required");
      if (!states.containsKey(resource)) return Optional.empty();
      Owner current = owners.get(resource);
      if (current != null && current.until().isAfter(clock.instant())) return Optional.empty();
      String token = UUID.randomUUID().toString();
      owners.put(resource, new Owner(token, clock.instant().plus(lease)));
      return Optional.of(new Handle(resource, token));
    }
  }

  private boolean held(String resource, String token) {
    Owner owner = owners.get(resource);
    return owner != null && owner.token().equals(token) && owner.until().isAfter(clock.instant());
  }

  private record Owner(String token, Instant until) {}

  private final class Handle implements LockHandle {
    private final String resource;
    private final String token;

    private Handle(String resource, String token) {
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
      if (lease.isNegative() || lease.isZero())
        throw new IllegalArgumentException("Positive lease required");
      synchronized (monitor) {
        if (!held(resource, token)) return false;
        owners.put(resource, new Owner(token, clock.instant().plus(lease)));
        return true;
      }
    }

    @Override
    public void release() {
      synchronized (monitor) {
        Owner owner = owners.get(resource);
        if (owner != null && owner.token().equals(token)) owners.remove(resource);
      }
    }
  }
}
