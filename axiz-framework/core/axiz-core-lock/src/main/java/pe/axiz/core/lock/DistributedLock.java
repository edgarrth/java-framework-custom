package pe.axiz.core.lock;

import java.time.Duration;
import java.util.Optional;

/** Distributed lock with lease: a crashed owner never blocks a resource beyond its lease. */
public interface DistributedLock {

  /**
   * Tries once to acquire the lock.
   *
   * @param resource resource name, e.g. {@code account:123}
   * @param lease lease duration
   * @return the handle, or empty when somebody else holds it
   */
  Optional<LockHandle> tryAcquire(String resource, Duration lease);
}
