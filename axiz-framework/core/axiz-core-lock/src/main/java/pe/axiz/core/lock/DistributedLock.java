package pe.axiz.core.lock;

import java.time.Duration;
import java.util.Optional;

/**
 * Lock distribuido con lease: un propietario caído nunca bloquea un recurso más allá de su lease.
 */
public interface DistributedLock {

  /**
   * Intenta adquirir el lock una sola vez.
   *
   * @param resource nombre del recurso, por ejemplo {@code account:123}
   * @param lease duración del lease
   * @return el handle, o vacío cuando otro lo tiene
   */
  Optional<LockHandle> tryAcquire(String resource, Duration lease);
}
