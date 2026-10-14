package pe.axiz.core.saga;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence of saga state with optimistic locking. */
public interface SagaStateStore {

  void create(SagaState state);

  /** Saves the new state only if the stored version is {@code state.version() - 1}. */
  void update(SagaState state);

  Optional<SagaState> find(String sagaId);

  /** Non-terminal sagas not updated since the instant (for resumption after crashes). */
  List<SagaState> findStale(Instant updatedBefore, int max);
}
