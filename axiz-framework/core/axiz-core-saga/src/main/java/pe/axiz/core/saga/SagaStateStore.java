package pe.axiz.core.saga;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import pe.axiz.core.lock.DistributedLock;

/** Persistence of saga state with optimistic locking. */
public interface SagaStateStore extends DistributedLock {

  void create(SagaState state);

  /** Atomically checks version, execution owner and unexpired lease before saving progress. */
  void update(SagaState state, String owner);

  Optional<SagaState> find(String sagaId);

  /** Non-terminal sagas not updated since the instant (for resumption after crashes). */
  List<SagaState> findStale(Instant updatedBefore, int max);
}
