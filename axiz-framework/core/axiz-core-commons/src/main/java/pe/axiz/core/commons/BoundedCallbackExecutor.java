package pe.axiz.core.commons;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Prestarted callback workers with lock-free bounded admission. Capacity includes active work and
 * queued callbacks. Exhaustion rejects immediately; no caller-runs or unbounded pending work.
 * Construct during startup and close with the owning transport.
 */
public class BoundedCallbackExecutor extends AbstractExecutorService {
  private final ThreadPoolExecutor workers;
  private final AtomicInteger pending = new AtomicInteger();
  private final int capacity;
  private final String description;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Extension point; starts only its private worker delegate and closes it if startup fails.")
  public BoundedCallbackExecutor(
      int workers, int queued, ThreadFactory threads, String description) {
    if (workers < 1) throw new IllegalArgumentException("Workers must be positive");
    if (queued < 0) throw new IllegalArgumentException("Queued capacity must not be negative");
    this.capacity = Math.addExact(workers, queued);
    this.description = Objects.requireNonNull(description);
    this.workers =
        new ThreadPoolExecutor(
            workers,
            workers,
            0,
            TimeUnit.MILLISECONDS,
            new LinkedTransferQueue<>(),
            threads,
            new ThreadPoolExecutor.AbortPolicy());
    try {
      if (this.workers.prestartAllCoreThreads() != workers)
        throw new IllegalStateException("Cannot start callback workers");
    } catch (RuntimeException | Error failure) {
      this.workers.shutdownNow();
      throw failure;
    }
  }

  @Override
  public void execute(Runnable command) {
    Objects.requireNonNull(command);
    for (; ; ) {
      int count = pending.get();
      if (count >= capacity)
        throw new RejectedExecutionException(description + " capacity exhausted");
      if (pending.compareAndSet(count, count + 1)) break;
    }
    try {
      workers.execute(
          () -> {
            try {
              command.run();
            } finally {
              pending.decrementAndGet();
            }
          });
    } catch (RuntimeException | Error failure) {
      pending.decrementAndGet();
      throw failure;
    }
  }

  /** Live counts only; callers cannot mutate the owned worker queue or bypass admission. */
  public final int getPoolSize() {
    return workers.getPoolSize();
  }

  public final int getActiveCount() {
    return workers.getActiveCount();
  }

  public final int getQueueSize() {
    return workers.getQueue().size();
  }

  @Override
  public void shutdown() {
    workers.shutdown();
  }

  @Override
  public List<Runnable> shutdownNow() {
    return workers.shutdownNow();
  }

  @Override
  public boolean isShutdown() {
    return workers.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return workers.isTerminated();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return workers.awaitTermination(timeout, unit);
  }
}
