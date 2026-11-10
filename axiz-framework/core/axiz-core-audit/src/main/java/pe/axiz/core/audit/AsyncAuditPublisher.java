package pe.axiz.core.audit;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.axiz.core.commons.BoundedCallbackExecutor;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.Stages;

/**
 * Real asynchronous publication: a bounded queue drained by a virtual thread. When the queue is
 * full the caller waits up to {@code offerTimeout}; if it is still full the event is written to the
 * fallback publisher (normally the log) so it is never silently lost.
 *
 * <p>The worker never dies because of a publisher: a delivery failure goes to the fallback, and a
 * fallback failure is counted in {@link #lostCount()} and logged with the event id. {@link
 * #close()} drains the queue; whatever the worker could not deliver in time is written to the
 * fallback by the closing thread, and the outcome is returned so shutdown can report it.
 */
public final class AsyncAuditPublisher implements AuditPublisher, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(AsyncAuditPublisher.class);

  private final AuditPublisher delegate;
  private final AuditPublisher fallback;

  private record Pending(AuditEvent event, Supplier<RequestContext.Scope> context) {}

  private final BlockingQueue<Pending> queue;
  private final Stages.ContextProvider context;
  private final Duration offerTimeout;
  private final Duration closeTimeout;
  private final @Nullable BoundedCallbackExecutor deliveries;
  private final long deliveryTimeoutNanos;
  private final Thread worker;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final AtomicLong delivered = new AtomicLong();
  private final AtomicLong fallbacks = new AtomicLong();
  private final AtomicLong failures = new AtomicLong();
  private final AtomicLong lost = new AtomicLong();

  /**
   * Result of {@link #close()}.
   *
   * @param drainedInTime the worker emptied the queue before the timeout
   * @param flushedToFallback events the closing thread wrote to the fallback
   * @param lost events lost since start (fallback failures)
   */
  public record Shutdown(boolean drainedInTime, int flushedToFallback, long lost) {}

  public AsyncAuditPublisher(
      AuditPublisher delegate, AuditPublisher fallback, int capacity, Duration offerTimeout) {
    this(delegate, fallback, capacity, offerTimeout, Duration.ofSeconds(10));
  }

  public AsyncAuditPublisher(
      AuditPublisher delegate,
      AuditPublisher fallback,
      int capacity,
      Duration offerTimeout,
      Duration closeTimeout) {
    this(delegate, fallback, capacity, offerTimeout, closeTimeout, () -> () -> () -> {});
  }

  /** Captures only the metadata selected by the integration, never an execution or transaction. */
  public AsyncAuditPublisher(
      AuditPublisher delegate,
      AuditPublisher fallback,
      int capacity,
      Duration offerTimeout,
      Duration closeTimeout,
      Stages.ContextProvider context) {
    this(delegate, fallback, capacity, offerTimeout, closeTimeout, context, null);
  }

  /** Bounds each delivery independently of queue admission and shutdown, with one owned worker. */
  public AsyncAuditPublisher(
      AuditPublisher delegate,
      AuditPublisher fallback,
      int capacity,
      Duration offerTimeout,
      Duration closeTimeout,
      Duration deliveryTimeout,
      Stages.ContextProvider context) {
    this(
        delegate,
        fallback,
        capacity,
        offerTimeout,
        closeTimeout,
        context,
        requireDeliveryTimeout(deliveryTimeout));
  }

  private static Duration requireDeliveryTimeout(Duration timeout) {
    if (!timeout.isPositive())
      throw new IllegalArgumentException("Audit delivery timeout must be positive");
    return timeout;
  }

  private AsyncAuditPublisher(
      AuditPublisher delegate,
      AuditPublisher fallback,
      int capacity,
      Duration offerTimeout,
      Duration closeTimeout,
      Stages.ContextProvider context,
      @Nullable Duration deliveryTimeout) {
    this.delegate = Objects.requireNonNull(delegate);
    this.fallback = Objects.requireNonNull(fallback);
    this.queue = new ArrayBlockingQueue<>(capacity);
    this.offerTimeout = Objects.requireNonNull(offerTimeout);
    this.closeTimeout = Objects.requireNonNull(closeTimeout);
    this.context = Objects.requireNonNull(context);
    this.deliveryTimeoutNanos = deliveryTimeout == null ? 0 : deliveryTimeout.toNanos();
    this.deliveries =
        deliveryTimeout == null
            ? null
            : new BoundedCallbackExecutor(
                1,
                1,
                Thread.ofVirtual().name("axiz-audit-delivery-", 0).factory(),
                "audit delivery");
    this.worker = Thread.ofVirtual().name("axiz-audit-publisher").start(this::drain);
  }

  @Override
  public void publish(AuditEvent event) {
    if (!running.get()) {
      toFallback(event);
      return;
    }
    try {
      if (!queue.offer(
          new Pending(event, context.capture()), offerTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
        toFallback(event);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      toFallback(event);
    }
  }

  /** Native facade acceptance: immediate offer, with the same observable fallback policy. */
  public void publishWithoutWait(AuditEvent event) {
    if (!running.get() || !queue.offer(new Pending(event, context.capture()))) toFallback(event);
  }

  private void drain() {
    while (running.get() || !queue.isEmpty()) {
      Pending event;
      try {
        event = queue.poll(200, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return; // the closing thread flushes what is left
      }
      if (event != null) {
        deliver(event);
      }
    }
  }

  private void deliver(Pending pending) {
    AuditEvent event = pending.event();
    try {
      var executor = deliveries;
      if (executor == null) {
        publishDelegate(pending);
      } else {
        var delivery = executor.submit(() -> publishDelegate(pending));
        try {
          delivery.get(deliveryTimeoutNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
          delivery.cancel(true);
          executor.purge();
          Thread.currentThread().interrupt();
          throw new AuditPublishException("Audit delivery interrupted", interrupted);
        } catch (TimeoutException timeout) {
          delivery.cancel(true);
          executor.purge();
          throw new AuditPublishException("Audit delivery timeout", timeout);
        } catch (ExecutionException failure) {
          throw new AuditPublishException("Audit delivery failed", Stages.unwrap(failure));
        }
      }
      delivered.incrementAndGet();
    } catch (RuntimeException e) {
      failures.incrementAndGet();
      LOG.error("Audit delivery failed for event {}, writing to fallback", event.id(), e);
      toFallback(event);
    }
  }

  private void publishDelegate(Pending pending) {
    try (var scope = pending.context().get()) {
      delegate.publish(pending.event());
    }
  }

  private void toFallback(AuditEvent event) {
    fallbacks.incrementAndGet();
    try {
      fallback.publish(event);
    } catch (RuntimeException e) {
      lost.incrementAndGet();
      LOG.error("Audit event {} lost: the fallback publisher failed too", event.id(), e);
    }
  }

  public int pending() {
    return queue.size();
  }

  public long deliveredCount() {
    return delivered.get();
  }

  public long fallbackCount() {
    return fallbacks.get();
  }

  public long failureCount() {
    return failures.get();
  }

  /** Events that neither the delegate nor the fallback accepted. */
  public long lostCount() {
    return lost.get();
  }

  /** {@code true} while the worker thread is draining the queue. */
  public boolean workerAlive() {
    return worker.isAlive();
  }

  @Override
  public void close() {
    shutdown();
  }

  /**
   * Stops accepting events, waits for the worker to drain the queue and writes anything left to the
   * fallback.
   */
  public Shutdown shutdown() {
    running.set(false);
    boolean drained;
    try {
      drained = worker.join(closeTimeout);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      drained = false;
    }
    if (!drained) {
      worker.interrupt();
    }
    int flushed = 0;
    Pending remaining;
    while ((remaining = queue.poll()) != null) {
      toFallback(remaining.event());
      flushed++;
    }
    var executor = deliveries;
    if (executor != null) executor.shutdownNow();
    if (!drained || flushed > 0 || lost.get() > 0) {
      LOG.warn(
          "Audit publisher closed: drained in time={}, {} event(s) written to the fallback, {}"
              + " lost",
          drained,
          flushed,
          lost.get());
    }
    return new Shutdown(drained, flushed, lost.get());
  }
}
