package pe.axiz.core.saga;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import pe.axiz.core.lock.DistributedLock;

/** Persistencia del estado de la saga con bloqueo optimista. */
public interface SagaStateStore extends DistributedLock {

  void create(SagaState state);

  /**
   * Verifica de forma atómica la versión, el propietario de la ejecución y que el lease no haya
   * expirado antes de guardar el progreso.
   */
  void update(SagaState state, String owner);

  Optional<SagaState> find(String sagaId);

  /** Sagas no terminales sin actualizar desde el instante (para reanudarlas tras caídas). */
  List<SagaState> findStale(Instant updatedBefore, int max);
}
