package pe.axiz.core.lock;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.axiz.core.commons.LeaseTiming;
import pe.axiz.core.errors.BusinessException;

/**
 * Serializes work on a resource: waits up to {@code waitTimeout} for the lock, renews the lease in
 * the background (virtual thread, every third of the lease) while the work runs and always releases
 * it. If the lock cannot be obtained the caller gets a 409 business error.
 *
 * <p>A lease that is lost while the work runs is never reported as a success: the store answered
 * that another owner holds it, or it could not be renewed before it expired. The thread running the
 * work is interrupted at that moment (cooperative cancellation: blocking calls end with an {@link
 * InterruptedException}), and the work ends in a {@link LockLostException} (503, retryable) instead
 * of returning its result; if the work failed on its own, its exception is thrown with the loss
 * attached as suppressed. Work with external effects receives the {@link LockLease} to check it
 * right before each effect and to use its token in conditional writes; see {@link LockLease} for
 * why the resource itself must also reject an old owner.
 *
 * <p>The lease is counted from before each request to the store, on the monotonic clock, minus a
 * margin for clock drift between this process and the store (1% of the lease plus 2 ms): time spent
 * waiting for the answer is time the store already counted. An acquisition that answers after its
 * lease ran out is released and not used (another owner may hold the resource by then); the
 * template keeps trying until {@code waitTimeout}.
 */
public final class LockTemplate {

  private static final Logger LOG = LoggerFactory.getLogger(LockTemplate.class);

  /** Work that receives its lease. */
  @FunctionalInterface
  public interface LeasedWork<T extends @Nullable Object> {
    T call(LockLease lease) throws Exception;
  }

  private final DistributedLock lock;
  private final Duration pollInterval;

  public LockTemplate(DistributedLock lock, Duration pollInterval) {
    this.lock = Objects.requireNonNull(lock);
    this.pollInterval = Objects.requireNonNull(pollInterval);
  }

  /**
   * Runs work under the lock.
   *
   * @throws BusinessException {@link LockErrors#BUSY} when the lock is not acquired in time
   * @throws LockLostException when the lease was lost before the work finished
   */
  public <T extends @Nullable Object> T withLock(
      String resource, Duration lease, Duration waitTimeout, Callable<T> work) throws Exception {
    return withLock(resource, lease, waitTimeout, held -> work.call());
  }

  /**
   * Runs work that checks its lease under the lock.
   *
   * @throws BusinessException {@link LockErrors#BUSY} when the lock is not acquired in time
   * @throws LockLostException when the lease was lost before the work finished
   */
  public <T extends @Nullable Object> T withLock(
      String resource, Duration lease, Duration waitTimeout, LeasedWork<T> work) throws Exception {
    Acquired acquired = acquire(resource, lease, waitTimeout);
    LockHandle handle = acquired.handle();
    Lease held = new Lease(handle, acquired.validUntil(), Thread.currentThread());
    AtomicBoolean done = new AtomicBoolean();
    Thread renewer =
        Thread.ofVirtual()
            .name("axiz-lock-renew-" + resource)
            .start(() -> renewLoop(held, lease, done));
    T result;
    try {
      result = work.call(held);
    } catch (Exception | Error failure) {
      boolean lost = held.finish();
      stop(done, renewer, handle);
      if (lost && failure instanceof InterruptedException) {
        throw held.lost(failure);
      }
      if (lost) {
        failure.addSuppressed(held.lost(null));
      }
      throw failure;
    }
    boolean lost = held.finish();
    stop(done, renewer, handle);
    if (lost) {
      throw held.lost(null);
    }
    return result;
  }

  private static void stop(AtomicBoolean done, Thread renewer, LockHandle handle) {
    done.set(true);
    renewer.interrupt();
    try {
      renewer.join(Duration.ofSeconds(1));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    handle.release();
  }

  /** A handle and the moment (monotonic nanos) its lease stops being trusted. */
  private record Acquired(LockHandle handle, long validUntil) {}

  private Acquired acquire(String resource, Duration lease, Duration waitTimeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + waitTimeout.toNanos();
    while (true) {
      long sent = System.nanoTime();
      Optional<LockHandle> h = lock.tryAcquire(resource, lease);
      if (h.isPresent()) {
        long validUntil = validUntil(sent, lease);
        if (System.nanoTime() - validUntil < 0) {
          return new Acquired(h.get(), validUntil);
        }
        LOG.warn(
            "Lock on {} acquired after its lease ran out ({} ms to answer): released, not used",
            resource,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sent));
        releaseQuietly(h.get());
      }
      if (System.nanoTime() - deadline > 0) {
        throw new BusinessException(
            LockErrors.BUSY, "lock not acquired", Map.of("resource", resource));
      }
      Thread.sleep(pollInterval.toMillis());
    }
  }

  /** Until when a lease asked for at {@code sent} is trusted: the lease minus the drift margin. */
  static long validUntil(long sent, Duration lease) {
    return LeaseTiming.validUntil(sent, lease);
  }

  private static void releaseQuietly(LockHandle handle) {
    try {
      handle.release();
    } catch (RuntimeException e) {
      // the store expires it anyway; releasing only shortens the wait of the next owner
      LOG.debug("Lock on {} not released: {}", handle.resource(), e.toString());
    }
  }

  /**
   * Renews every third of the lease. A renewal the store refuses loses the lease at once; one that
   * fails (store unavailable) is retried, and the lease is lost when its time runs out without a
   * renewal.
   */
  static void renewLoop(Lease held, Duration lease, AtomicBoolean done) {
    long every = Math.max(10, lease.toMillis() / 3);
    while (!done.get()) {
      try {
        Thread.sleep(every);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      if (done.get()) {
        return;
      }
      long attempt = System.nanoTime();
      try {
        if (held.handle.renew(lease)) {
          held.heldUntil = validUntil(attempt, lease);
          continue;
        }
        held.lose(null);
      } catch (RuntimeException e) {
        if (attempt - held.heldUntil < 0) {
          LOG.warn("Lock on {} not renewed, retrying: {}", held.resource(), e.toString());
          continue;
        }
        held.lose(e);
      }
      LOG.warn("Lock on {} was lost before the work finished", held.resource());
      return;
    }
  }

  /** State of one acquisition, shared by the work and the renewer. */
  static final class Lease implements LockLease {

    private final LockHandle handle;
    private final Thread worker;
    // monotonic nanos (System.nanoTime) after which the lease is no longer trusted
    private volatile long heldUntil;
    private volatile boolean lost;
    private volatile @Nullable Throwable cause;
    // guarded by this: lose() and finish() decide together whether the work gets interrupted
    private boolean running = true;
    private boolean interrupted;

    Lease(LockHandle handle, long heldUntil, Thread worker) {
      this.handle = handle;
      this.heldUntil = heldUntil;
      this.worker = worker;
    }

    /** Marks the lease lost and interrupts the work if it is still running. */
    synchronized void lose(@Nullable Throwable why) {
      if (lost) {
        return;
      }
      cause = why;
      lost = true;
      if (running) {
        interrupted = true;
        worker.interrupt();
      }
    }

    /**
     * Called by the work's thread when the work ended; clears the interrupt this lease caused.
     *
     * @return whether the lease was lost
     */
    boolean finish() {
      boolean interruptedByLease;
      synchronized (this) {
        running = false;
        interruptedByLease = interrupted;
      }
      if (interruptedByLease) {
        Thread.interrupted();
      }
      return !held();
    }

    LockLostException lost(@Nullable Throwable during) {
      LockLostException e = new LockLostException(handle.resource(), cause);
      if (during != null) {
        e.addSuppressed(during);
      }
      return e;
    }

    @Override
    public String resource() {
      return handle.resource();
    }

    @Override
    public String token() {
      return handle.token();
    }

    @Override
    public boolean held() {
      return !lost && System.nanoTime() - heldUntil < 0;
    }

    @Override
    public void ensureHeld() {
      if (!held()) {
        throw lost(null);
      }
    }
  }
}
