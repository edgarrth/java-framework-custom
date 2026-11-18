package pe.axiz.core.audit;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.axiz.core.concurrent.BoundedCallbackExecutor;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.context.RequestContext;

/**
 * Bounded asynchronous delivery with independently bounded admission, delivery and fallback.
 * Shutdown shares one total budget across draining, cancellation, flushing and executor
 * termination. A callback ignoring interruption can outlive that budget; {@link
 * Shutdown#terminated()} exposes this explicitly. A timed-out acknowledgement is uncertain and may
 * physically complete later; consumers must tolerate duplicates. Publication after shutdown is
 * rejected.
 */
public final class AsyncAuditPublisher implements AuditPublisher, AuditQueueMetrics, AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(AsyncAuditPublisher.class);

  private record Pending(AuditEvent event, Supplier<RequestContext.Scope> context) {}

  private final AuditPublisher delegate;
  private final AuditPublisher fallback;
  private final BlockingQueue<Pending> queue;
  private final int capacity;
  private final AtomicInteger queued = new AtomicInteger();
  private final Map<Object, CompletableFuture<Void>> nativeFallbacks = new ConcurrentHashMap<>();
  private final Stages.ContextProvider context;
  private final long offerTimeoutNanos;
  private final long closeTimeoutNanos;
  private final long deliveryTimeoutNanos;
  private final @Nullable BoundedCallbackExecutor deliveries;
  private final BoundedCallbackExecutor fallbackDeliveries;
  private final Thread worker;
  private final ReentrantLock admission = new ReentrantLock();
  private final Condition space = admission.newCondition();
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final AtomicLong delivered = new AtomicLong();
  private final AtomicLong fallbacks = new AtomicLong();
  private final AtomicLong failures = new AtomicLong();
  private final AtomicLong lost = new AtomicLong();
  private final AtomicLong unconfirmed = new AtomicLong();
  private final AtomicInteger callerFallbacks = new AtomicInteger();
  private volatile long shutdownDeadline = Long.MAX_VALUE;
  private @Nullable Shutdown result;
  private final Object shutdownMonitor = new Object();

  /**
   * Shutdown snapshot. Counts are final when terminated is true; otherwise a callback or the
   * drainer may still be finishing. Unconfirmed means fallback acceptance is unknown, not confirmed
   * loss. Flushed counts only events taken from the queue by the closing thread.
   */
  public record Shutdown(
      boolean drainedInTime,
      int flushedToFallback,
      long lost,
      boolean terminated,
      long unconfirmed,
      int pending) {}

  /** All execution paths, including fallback, use a finite delivery timeout. */
  public AsyncAuditPublisher(
      AuditPublisher delegate,
      AuditPublisher fallback,
      int capacity,
      Duration offerTimeout,
      Duration closeTimeout,
      Duration deliveryTimeout,
      Stages.ContextProvider context) {
    this(delegate, fallback, capacity, offerTimeout, closeTimeout, deliveryTimeout, context, false);
  }

  /** Native submissions are non-blocking by contract; the owned drainer awaits just their ACK. */
  static AsyncAuditPublisher forNative(
      AsyncAuditEventPublisher target,
      AuditPublisher fallback,
      int capacity,
      Duration deliveryTimeout,
      Stages.ContextProvider context) {
    Objects.requireNonNull(target);
    AuditPublisher acknowledged =
        event -> {
          var pending = Stages.call(() -> target.publish(event)).toCompletableFuture();
          try {
            pending.get(deliveryTimeout.toNanos(), TimeUnit.NANOSECONDS);
          } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new AuditPublishException("Native audit interrupted", interrupted);
          } catch (TimeoutException timeout) {
            pending.cancel(true);
            throw new AuditPublishException("Native audit acknowledgement timeout", timeout);
          } catch (ExecutionException failure) {
            throw new AuditPublishException("Native audit delivery failed", Stages.unwrap(failure));
          }
        };
    return new AsyncAuditPublisher(
        acknowledged,
        fallback,
        capacity,
        Duration.ZERO,
        Duration.ofSeconds(10),
        deliveryTimeout,
        context,
        true);
  }

  private AsyncAuditPublisher(
      AuditPublisher delegate,
      AuditPublisher fallback,
      int capacity,
      Duration offerTimeout,
      Duration closeTimeout,
      Duration deliveryTimeout,
      Stages.ContextProvider context,
      boolean nativeAcknowledgementBounded) {
    this.delegate = Objects.requireNonNull(delegate);
    this.fallback = Objects.requireNonNull(fallback);
    if (offerTimeout.isNegative() || !closeTimeout.isPositive() || !deliveryTimeout.isPositive())
      throw new IllegalArgumentException("Audit timeouts must be positive (offer may be zero)");
    if (capacity < 1) throw new IllegalArgumentException("Audit queue capacity must be positive");
    this.capacity = capacity;
    this.queue = new LinkedTransferQueue<>();
    this.offerTimeoutNanos = offerTimeout.toNanos();
    this.closeTimeoutNanos = closeTimeout.toNanos();
    this.deliveryTimeoutNanos = deliveryTimeout.toNanos();
    this.context = Objects.requireNonNull(context);
    deliveries = nativeAcknowledgementBounded ? null : executor("axiz-audit-delivery-");
    fallbackDeliveries = executor("axiz-audit-fallback-");
    worker = Thread.ofVirtual().name("axiz-audit-publisher").start(this::drain);
  }

  private static BoundedCallbackExecutor executor(String name) {
    return new BoundedCallbackExecutor(1, 1, Thread.ofVirtual().name(name, 0).factory(), name);
  }

  @Override
  public void publish(AuditEvent event) {
    enqueue(event, offerTimeoutNanos);
  }

  /** Immediate admission for native callers; never waits for queue space. */
  public void publishWithoutWait(AuditEvent event) {
    Objects.requireNonNull(event);
    callerFallbacks.incrementAndGet();
    try {
      if (!running.get()) throw new IllegalStateException("Audit publisher is closed");
      Pending pending = new Pending(event, context.capture());
      if (admission.tryLock()) {
        try {
          if (!running.get()) throw new IllegalStateException("Audit publisher is closed");
          if (offer(pending)) return;
        } finally {
          admission.unlock();
        }
      }
      fallbackWithoutWait(event);
    } finally {
      callerFallbacks.decrementAndGet();
    }
  }

  // Producers hold admission; the queue itself uses lock-free, non-waiting offers.
  private boolean offer(Pending pending) {
    if (queued.get() >= capacity) return false;
    queued.incrementAndGet();
    if (!queue.offer(pending)) {
      queued.decrementAndGet();
      return false;
    }
    return true;
  }

  private static AuditEvent fallbackEvent(AuditEvent event) {
    return event.withPersistence(
        "UNCERTAIN".equals(event.persistence()) ? "UNCERTAIN" : "NOT_CONFIRMED");
  }

  private void fallbackWithoutWait(AuditEvent event) {
    fallbacks.incrementAndGet();
    var completion = new CompletableFuture<Void>();
    Object admissionKey = new Object();
    nativeFallbacks.put(admissionKey, completion);
    Stages.observe(
        completion,
        (ignored, failure) -> {
          if (failure instanceof TimeoutException || failure instanceof CancellationException)
            unconfirmed.incrementAndGet();
          else if (failure != null) lost.incrementAndGet();
          // Removal is the completion barrier used by shutdown's final snapshot.
          nativeFallbacks.remove(admissionKey);
        },
        failure -> {
          nativeFallbacks.remove(admissionKey);
          lost.incrementAndGet();
          LOG.error(
              "Audit fallback completion observation failed type={}", failure.getClass().getName());
        });
    try {
      fallbackDeliveries.execute(
          () -> {
            try {
              fallback.publish(fallbackEvent(event));
              completion.complete(null);
            } catch (RuntimeException | Error failure) {
              completion.completeExceptionally(failure);
              LOG.error(
                  "Audit fallback failed for event {} type={}",
                  event.id(),
                  failure.getClass().getName());
            }
          });
      completion.orTimeout(deliveryTimeoutNanos, TimeUnit.NANOSECONDS);
    } catch (RuntimeException rejected) {
      completion.completeExceptionally(rejected);
    }
  }

  private void enqueue(AuditEvent event, long wait) {
    Pending pending = new Pending(Objects.requireNonNull(event), context.capture());
    boolean accepted = false;
    admission.lock();
    try {
      while (running.get()) {
        if (offer(pending)) {
          accepted = true;
          break;
        }
        if (wait <= 0) break;
        try {
          wait = space.awaitNanos(wait);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      if (!running.get()) throw new IllegalStateException("Audit publisher is closed");
      if (!accepted) callerFallbacks.incrementAndGet();
    } finally {
      admission.unlock();
    }
    if (!accepted) {
      try {
        toFallback(event);
      } finally {
        callerFallbacks.decrementAndGet();
      }
    }
  }

  private void drain() {
    while (running.get() || !queue.isEmpty()) {
      if (Thread.currentThread().isInterrupted()) return;
      try {
        Pending event = queue.poll(200, TimeUnit.MILLISECONDS);
        if (event != null) {
          queued.decrementAndGet();
          admission.lock();
          try {
            space.signalAll();
          } finally {
            admission.unlock();
          }
          deliver(event);
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private long remaining() {
    return shutdownDeadline == Long.MAX_VALUE
        ? deliveryTimeoutNanos
        : Math.max(0, Math.min(deliveryTimeoutNanos, shutdownDeadline - System.nanoTime()));
  }

  private void invoke(BoundedCallbackExecutor executor, Runnable callback) {
    long budget = remaining();
    if (budget <= 0)
      throw new AuditPublishException(
          "Audit shutdown budget exhausted", new TimeoutException("shutdown"));
    var task = executor.submit(callback);
    try {
      task.get(budget, TimeUnit.NANOSECONDS);
    } catch (InterruptedException interrupted) {
      task.cancel(true);
      Thread.currentThread().interrupt();
      throw new AuditPublishException("Audit delivery interrupted", interrupted);
    } catch (TimeoutException timeout) {
      task.cancel(true);
      throw new AuditPublishException("Audit acknowledgement timeout", timeout);
    } catch (ExecutionException failure) {
      Throwable cause = Stages.unwrap(failure);
      if (cause instanceof Error fatal) throw fatal;
      throw new AuditPublishException("Audit delivery failed", cause);
    }
  }

  private void deliver(Pending pending) {
    try {
      var executor = deliveries;
      if (executor == null) publishDelegate(pending);
      else invoke(executor, () -> publishDelegate(pending));
      delivered.incrementAndGet();
    } catch (RuntimeException failure) {
      failures.incrementAndGet();
      LOG.error(
          "Audit delivery failed for event {}, writing to fallback type={}",
          pending.event().id(),
          failure.getClass().getName());
      toFallback(pending.event());
    }
  }

  private void publishDelegate(Pending pending) {
    try (var scope = pending.context().get()) {
      delegate.publish(pending.event());
    }
  }

  private void toFallback(AuditEvent event) {
    fallbacks.incrementAndGet();
    // Cancellation of a delivery must still allow its fallback within the shared deadline.
    boolean interrupted = Thread.interrupted();
    try {
      invoke(fallbackDeliveries, () -> fallback.publish(fallbackEvent(event)));
    } catch (RuntimeException failure) {
      if (failure instanceof RejectedExecutionException
          || (failure instanceof AuditPublishException
              && failure.getCause() != null
              && !(failure.getCause() instanceof TimeoutException)
              && !(failure.getCause() instanceof InterruptedException))) {
        lost.incrementAndGet();
      } else {
        unconfirmed.incrementAndGet();
      }
      LOG.error(
          "Audit fallback did not confirm event {} type={}",
          event.id(),
          failure.getClass().getName());
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  @Override
  public int pending() {
    return queued.get();
  }

  @Override
  public long deliveredCount() {
    return delivered.get();
  }

  @Override
  public long fallbackCount() {
    return fallbacks.get();
  }

  @Override
  public long failureCount() {
    return failures.get();
  }

  /** Confirmed failures of the fallback; uncertain acceptance is counted separately. */
  @Override
  public long lostCount() {
    return lost.get();
  }

  @Override
  public long unconfirmedCount() {
    return unconfirmed.get();
  }

  @Override
  public boolean workerAlive() {
    return worker.isAlive();
  }

  @Override
  public void close() {
    shutdown();
  }

  private boolean joinWorker(long deadline) throws InterruptedException {
    long nanos = deadline - System.nanoTime();
    return !worker.isAlive() || (nanos > 0 && worker.join(Duration.ofNanos(nanos)));
  }

  /** Idempotent shutdown. No new admission can race with the final queue flush. */
  public Shutdown shutdown() {
    synchronized (shutdownMonitor) {
      return shutdownOwned();
    }
  }

  private Shutdown shutdownOwned() {
    if (result != null) return result;
    long started = System.nanoTime();
    shutdownDeadline = started + closeTimeoutNanos;
    admission.lock();
    try {
      running.set(false);
      space.signalAll();
    } finally {
      admission.unlock();
    }
    boolean drained = false;
    int flushed = 0;
    try {
      drained = joinWorker(started + closeTimeoutNanos / 2) && queue.isEmpty();
      if (!drained) {
        worker.interrupt();
        // Reserve time for the closing thread to flush and terminate resources.
        joinWorker(started + closeTimeoutNanos * 3 / 4);
      }
      Pending pending;
      while (remaining() > 0 && (pending = queue.poll()) != null) {
        queued.decrementAndGet();
        toFallback(pending.event());
        flushed++;
      }
    } catch (InterruptedException interrupted) {
      worker.interrupt();
      Thread.currentThread().interrupt();
    } finally {
      if (deliveries != null) deliveries.shutdownNow();
      nativeFallbacks.values().forEach(completion -> completion.cancel(false));
      fallbackDeliveries.shutdownNow();
    }
    try {
      joinWorker(shutdownDeadline);
      if (deliveries != null)
        deliveries.awaitTermination(
            Math.max(0, shutdownDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      fallbackDeliveries.awaitTermination(
          Math.max(0, shutdownDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    boolean terminated =
        !worker.isAlive()
            && (deliveries == null || deliveries.isTerminated())
            && fallbackDeliveries.isTerminated()
            && queue.isEmpty()
            && callerFallbacks.get() == 0
            && nativeFallbacks.isEmpty();
    result =
        new Shutdown(drained, flushed, lost.get(), terminated, unconfirmed.get(), queue.size());
    if (!terminated || lost.get() > 0 || unconfirmed.get() > 0)
      LOG.warn("Audit shutdown: {}", result);
    return result;
  }
}
