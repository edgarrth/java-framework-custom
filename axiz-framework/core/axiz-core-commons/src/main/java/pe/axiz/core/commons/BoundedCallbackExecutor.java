package pe.axiz.core.commons;

import java.util.Objects;
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
public class BoundedCallbackExecutor extends ThreadPoolExecutor {
  private final AtomicInteger pending = new AtomicInteger();
  private final int capacity;
  private final String description;

  public BoundedCallbackExecutor(
      int workers, int queued, ThreadFactory threads, String description) {
    super(
        workers,
        workers,
        0,
        TimeUnit.MILLISECONDS,
        new LinkedTransferQueue<>(),
        threads,
        new AbortPolicy());
    if (queued < 0) throw new IllegalArgumentException("Queued capacity must not be negative");
    this.capacity = Math.addExact(workers, queued);
    this.description = Objects.requireNonNull(description);
    try {
      if (prestartAllCoreThreads() != workers)
        throw new IllegalStateException("Cannot start callback workers");
    } catch (RuntimeException | Error failure) {
      shutdownNow();
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
      super.execute(
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
}
