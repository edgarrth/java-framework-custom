package pe.axiz.core.lock;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.axiz.core.errors.BusinessException;

/**
 * Serializes work on a resource: waits up to {@code waitTimeout} for the lock, renews the lease in
 * the background (virtual thread, every third of the lease) while the work runs and always releases
 * it. If the lock cannot be obtained the caller gets a 409 business error.
 */
public final class LockTemplate {

  private static final Logger LOG = LoggerFactory.getLogger(LockTemplate.class);

  private final DistributedLock lock;
  private final Duration pollInterval;

  public LockTemplate(DistributedLock lock, Duration pollInterval) {
    this.lock = Objects.requireNonNull(lock);
    this.pollInterval = Objects.requireNonNull(pollInterval);
  }

  public <T> T withLock(String resource, Duration lease, Duration waitTimeout, Callable<T> work)
      throws Exception {
    LockHandle handle = acquire(resource, lease, waitTimeout);
    AtomicBoolean done = new AtomicBoolean();
    Thread renewer =
        Thread.ofVirtual()
            .name("axiz-lock-renew-" + resource)
            .start(() -> renewLoop(handle, lease, done));
    try {
      return work.call();
    } finally {
      done.set(true);
      renewer.interrupt();
      handle.release();
    }
  }

  private LockHandle acquire(String resource, Duration lease, Duration waitTimeout)
      throws InterruptedException {
    Instant deadline = Instant.now().plus(waitTimeout);
    while (true) {
      Optional<LockHandle> h = lock.tryAcquire(resource, lease);
      if (h.isPresent()) {
        return h.get();
      }
      if (Instant.now().isAfter(deadline)) {
        throw new BusinessException(
            LockErrors.BUSY, "lock not acquired", Map.of("resource", resource));
      }
      Thread.sleep(pollInterval.toMillis());
    }
  }

  private static void renewLoop(LockHandle handle, Duration lease, AtomicBoolean done) {
    long every = Math.max(10, lease.toMillis() / 3);
    while (!done.get()) {
      try {
        Thread.sleep(every);
      } catch (InterruptedException e) {
        return;
      }
      if (!done.get() && !handle.renew(lease)) {
        LOG.warn("Lock on {} was lost before the work finished", handle.resource());
        return;
      }
    }
  }
}
