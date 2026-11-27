package pe.axiz.core.audit;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.concurrent.Stages;

/**
 * Encolado no bloqueante sobre la cola acotada compartida. Solo su worker virtual propio espera el
 * acuse del transporte nativo; ningún hilo de petición espera espacio en la cola ni E/S de red. Los
 * eventos inmutables llevan los metadatos de auditoría. Este modo intencionalmente no propaga una
 * transacción.
 */
public final class QueuedAsyncAuditPublisher
    implements AsyncAuditEventPublisher, AuditQueueMetrics, AutoCloseable {
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

  @Override
  public int pending() {
    return queue.pending();
  }

  @Override
  public long deliveredCount() {
    return queue.deliveredCount();
  }

  @Override
  public long fallbackCount() {
    return queue.fallbackCount();
  }

  @Override
  public long failureCount() {
    return queue.failureCount();
  }

  @Override
  public long lostCount() {
    return queue.lostCount();
  }

  @Override
  public long unconfirmedCount() {
    return queue.unconfirmedCount();
  }

  @Override
  public boolean workerAlive() {
    return queue.workerAlive();
  }

  @Override
  public void close() {
    queue.close();
  }
}
