package pe.axiz.core.audit;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
  private final BlockingQueue<AuditEvent> queue;
  private final Duration offerTimeout;
  private final Duration closeTimeout;
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
    this.delegate = Objects.requireNonNull(delegate);
    this.fallback = Objects.requireNonNull(fallback);
    this.queue = new ArrayBlockingQueue<>(capacity);
    this.offerTimeout = Objects.requireNonNull(offerTimeout);
    this.closeTimeout = Objects.requireNonNull(closeTimeout);
    this.worker = Thread.ofVirtual().name("axiz-audit-publisher").start(this::drain);
  }

  @Override
  public void publish(AuditEvent event) {
    if (!running.get()) {
      toFallback(event);
      return;
    }
    try {
      if (!queue.offer(event, offerTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
        toFallback(event);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      toFallback(event);
    }
  }

  private void drain() {
    while (running.get() || !queue.isEmpty()) {
      AuditEvent event;
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

  private void deliver(AuditEvent event) {
    try {
      delegate.publish(event);
      delivered.incrementAndGet();
    } catch (RuntimeException e) {
      failures.incrementAndGet();
      LOG.error("Audit delivery failed for event {}, writing to fallback", event.id(), e);
      toFallback(event);
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
    AuditEvent remaining;
    while ((remaining = queue.poll()) != null) {
      toFallback(remaining);
      flushed++;
    }
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
