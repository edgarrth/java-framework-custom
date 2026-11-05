package pe.axiz.core.audit;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import pe.axiz.core.commons.Stages;

/**
 * Non-blocking enqueue over the shared bounded queue. Only its owned virtual worker awaits the
 * native transport acknowledgement; no request thread waits for queue space or network I/O.
 * Immutable events carry audit metadata. This mode intentionally does not propagate a transaction.
 */
public final class QueuedAsyncAuditPublisher implements AsyncAuditEventPublisher, AutoCloseable {
  private final AsyncAuditPublisher queue;

  public QueuedAsyncAuditPublisher(
      AsyncAuditEventPublisher target,
      AuditPublisher fallback,
      int capacity,
      Duration deliveryTimeout) {
    this(target, fallback, capacity, deliveryTimeout, () -> () -> () -> {});
  }

  public QueuedAsyncAuditPublisher(
      AsyncAuditEventPublisher target,
      AuditPublisher fallback,
      int capacity,
      Duration deliveryTimeout,
      Stages.ContextProvider context) {
    if (!deliveryTimeout.isPositive())
      throw new IllegalArgumentException("Audit delivery timeout must be positive");
    queue =
        new AsyncAuditPublisher(
            event -> {
              var pending = Stages.call(() -> target.publish(event)).toCompletableFuture();
              try {
                var _ = pending.get(deliveryTimeout.toNanos(), TimeUnit.NANOSECONDS);
              } catch (InterruptedException interrupted) {
                pending.cancel(true);
                Thread.currentThread().interrupt();
                throw new AuditPublishException("Audit worker interrupted", interrupted);
              } catch (TimeoutException timeout) {
                pending.cancel(true);
                throw new AuditPublishException("Audit acknowledgement timeout", timeout);
              } catch (ExecutionException failure) {
                throw new AuditPublishException("Audit delivery failed", Stages.unwrap(failure));
              }
            },
            fallback,
            capacity,
            Duration.ZERO,
            Duration.ofSeconds(10),
            context);
  }

  @Override
  public CompletionStage<Void> publish(AuditEvent event) {
    queue.publishWithoutWait(event);
    return CompletableFuture.completedFuture(null);
  }

  public AsyncAuditPublisher queue() {
    return queue;
  }

  @Override
  public void close() {
    queue.close();
  }
}
