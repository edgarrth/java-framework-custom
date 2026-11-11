package pe.axiz.core.audit;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
    queue = AsyncAuditPublisher.forNative(target, fallback, capacity, deliveryTimeout, context);
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
