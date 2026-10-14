package pe.axiz.core.saga;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory saga store for tests and local runs. */
public final class InMemorySagaStateStore implements SagaStateStore {

  private final Map<String, SagaState> states = new ConcurrentHashMap<>();

  @Override
  public void create(SagaState state) {
    if (states.putIfAbsent(state.sagaId(), state) != null) {
      throw new IllegalStateException("Saga " + state.sagaId() + " already exists");
    }
  }

  @Override
  public void update(SagaState state) {
    states.compute(
        state.sagaId(),
        (k, current) -> {
          if (current == null || current.version() != state.version() - 1) {
            throw new SagaConcurrencyException(state.sagaId());
          }
          return state;
        });
  }

  @Override
  public Optional<SagaState> find(String sagaId) {
    return Optional.ofNullable(states.get(sagaId));
  }

  @Override
  public List<SagaState> findStale(Instant updatedBefore, int max) {
    return states.values().stream()
        .filter(s -> !s.status().terminal() && s.updatedAt().isBefore(updatedBefore))
        .limit(max)
        .toList();
  }
}
