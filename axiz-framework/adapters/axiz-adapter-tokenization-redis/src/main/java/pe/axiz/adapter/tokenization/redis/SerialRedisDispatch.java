package pe.axiz.adapter.tokenization.redis;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** One accepted native command at a time; no monitor, blocking worker or wait for I/O. */
final class SerialRedisDispatch {
  private final int capacity;
  private final long timeoutNanos;
  private final LongSupplier ticker;
  private final AtomicInteger pending = new AtomicInteger();
  private final AtomicInteger work = new AtomicInteger();
  private final ConcurrentLinkedQueue<Task<?>> queue = new ConcurrentLinkedQueue<>();
  // Only the drain owner reads or writes this reference.
  private @Nullable Task<?> active;

  SerialRedisDispatch(int capacity, Duration timeout) {
    this(capacity, timeout, System::nanoTime);
  }

  SerialRedisDispatch(int capacity, Duration timeout, LongSupplier ticker) {
    if (capacity < 1 || timeout.isNegative() || timeout.isZero())
      throw new IllegalArgumentException("Invalid native Redis queue policy");
    this.capacity = capacity;
    this.timeoutNanos = timeout.toNanos();
    this.ticker = Objects.requireNonNull(ticker);
  }

  <T> CompletionStage<T> submit(Supplier<CompletionStage<T>> action) {
    if (pending.incrementAndGet() > capacity) {
      pending.decrementAndGet();
      return CompletableFuture.failedFuture(
          new RejectedExecutionException("Native Redis queue full"));
    }
    Task<T> task = new Task<>(action);
    queue.add(task);
    drain();
    return task.result;
  }

  private void drain() {
    if (work.getAndIncrement() != 0) return;
    int missed = 1;
    do {
      Task<?> current = active;
      if (current == null || current.done) {
        if (current != null) {
          active = null;
          pending.decrementAndGet();
          current.publish();
        }
        Task<?> next = queue.poll();
        if (next != null) {
          active = next;
          next.start();
        }
      }
      missed = work.addAndGet(-missed);
    } while (missed != 0);
  }

  private final class Task<T> {
    final Supplier<CompletionStage<T>> action;
    final long enqueuedAt = ticker.getAsLong();
    final CompletableFuture<T> result = new CompletableFuture<>();
    @Nullable T value;
    @Nullable Throwable failure;
    volatile boolean done;

    Task(Supplier<CompletionStage<T>> action) {
      this.action = action;
    }

    void start() {
      if (ticker.getAsLong() - enqueuedAt >= timeoutNanos) {
        failure = new TimeoutException("Native Redis queue deadline elapsed");
        done = true;
        drain();
        return;
      }
      try {
        // Register only after dispatch returns. A completion cannot start another writer while
        // Lettuce is still leaving its original command submission's critical section.
        CompletionStage<T> source = action.get();
        source.whenComplete(
            (value, failure) -> {
              this.failure = failure;
              this.value = value;
              if (failure == null && value == null)
                this.failure = new IllegalStateException("Missing Redis result");
              done = true;
              drain();
            });
      } catch (Throwable failure) {
        this.failure = failure;
        done = true;
        drain();
      }
    }

    void publish() {
      if (failure != null) result.completeExceptionally(failure);
      else result.complete(Objects.requireNonNull(value));
    }
  }
}
