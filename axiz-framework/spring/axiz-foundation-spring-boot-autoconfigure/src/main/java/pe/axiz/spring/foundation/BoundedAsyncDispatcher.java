package pe.axiz.spring.foundation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.BoundedCallbackExecutor;

/**
 * Bounded submission boundary for transports whose asynchronous send can block on admission.
 * Capacity covers queued submissions AND broker acknowledgements; saturation never runs on callers.
 * Construct at startup, close with the transport. Cancellation after send cannot retract a message.
 */
public final class BoundedAsyncDispatcher implements AutoCloseable {
  private final BoundedCallbackExecutor executor;
  private final ScheduledThreadPoolExecutor deadlines;
  private final Semaphore capacity;
  private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final long timeoutNanos;
  private final @Nullable MeterRegistry registry;
  private final List<Meter> meters;
  private final @Nullable Counter rejected;
  private final @Nullable Timer latency;

  public BoundedAsyncDispatcher(
      String name, int workers, int queued, Duration timeout, @Nullable MeterRegistry registry) {
    if (workers < 1 || queued < 0 || timeout.isZero() || timeout.isNegative())
      throw new IllegalArgumentException("Positive workers/timeout and nonnegative queue required");
    Objects.requireNonNull(name);
    this.registry = registry;
    this.timeoutNanos = timeout.toNanos();
    this.capacity = new Semaphore(Math.addExact(workers, queued));
    this.executor =
        new BoundedCallbackExecutor(
            workers, queued, Thread.ofPlatform().daemon().name(name + "-send-", 0).factory(), name);
    this.deadlines =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().daemon().name(name + "-deadline-", 0).factory());
    this.deadlines.setRemoveOnCancelPolicy(true);
    if (registry == null) {
      this.rejected = null;
      this.latency = null;
      this.meters = List.of();
    } else {
      var pendingGauge =
          Gauge.builder("axiz.transport.pending", pending, Set::size)
              .tag("transport", name)
              .register(registry);
      var queuedGauge =
          Gauge.builder("axiz.transport.queued", executor, e -> e.getQueue().size())
              .tag("transport", name)
              .register(registry);
      this.rejected = registry.counter("axiz.transport.rejected", "transport", name);
      this.latency = registry.timer("axiz.transport.latency", "transport", name);
      this.meters = List.of(pendingGauge, queuedGauge, rejected, latency);
    }
  }

  public <T> CompletionStage<T> submit(Supplier<? extends CompletionStage<T>> operation) {
    Objects.requireNonNull(operation);
    if (closed.get() || !capacity.tryAcquire()) {
      if (rejected != null) rejected.increment();
      return CompletableFuture.failedFuture(
          new RejectedExecutionException("Transport closed or saturated"));
    }
    CompletableFuture<T> result = new CompletableFuture<>();
    pending.add(result);
    AtomicInteger state = new AtomicInteger();
    Runnable release =
        () -> {
          if (state.getAndSet(2) != 2) {
            pending.remove(result);
            capacity.release();
          }
        };
    long started = System.nanoTime();
    result.whenComplete(
        (value, error) -> {
          // A timeout cannot free an accepted Kafka send: the broker may still acknowledge it.
          if (state.compareAndSet(0, 2)) {
            pending.remove(result);
            capacity.release();
          }
          if (latency != null) latency.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        });
    try {
      var deadline =
          deadlines.schedule(
              () ->
                  result.completeExceptionally(
                      new TimeoutException("Broker acknowledgement deadline exceeded")),
              timeoutNanos,
              TimeUnit.NANOSECONDS);
      result.whenComplete((value, error) -> deadline.cancel(false));
      if (closed.get())
        result.completeExceptionally(new RejectedExecutionException("Transport closed"));
      else
        executor.execute(
            () -> {
              if (!state.compareAndSet(0, 1)) return;
              try {
                operation
                    .get()
                    .whenComplete(
                        (value, error) -> {
                          release.run();
                          if (error != null) result.completeExceptionally(error);
                          else result.complete(value);
                        });
              } catch (RuntimeException | Error failure) {
                release.run();
                result.completeExceptionally(failure);
              }
            });
    } catch (RejectedExecutionException failure) {
      if (rejected != null) rejected.increment();
      release.run();
      result.completeExceptionally(failure);
    }
    return result;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      pending.forEach(
          f -> f.completeExceptionally(new RejectedExecutionException("Transport closed")));
      executor.shutdownNow();
      deadlines.shutdownNow();
      pending.clear();
      if (registry != null) meters.forEach(registry::remove);
    }
  }
}
