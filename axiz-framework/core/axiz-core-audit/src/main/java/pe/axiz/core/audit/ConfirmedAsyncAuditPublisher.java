package pe.axiz.core.audit;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import pe.axiz.core.concurrent.Stages;

/**
 * Native asynchronous acknowledgement; audit delivery failure cannot revert a confirmed business.
 */
public final class ConfirmedAsyncAuditPublisher
    implements AsyncAuditEventPublisher, AsyncAuditPublisherDecorator, AutoCloseable {
  private final AsyncAuditEventPublisher target;
  private final AtomicLong unconfirmed = new AtomicLong();
  private final AtomicBoolean closed = new AtomicBoolean();

  public ConfirmedAsyncAuditPublisher(AsyncAuditEventPublisher target) {
    this.target = Objects.requireNonNull(target);
  }

  @Override
  public CompletionStage<Void> publish(AuditEvent event) {
    if (closed.get())
      return CompletableFuture.failedFuture(new IllegalStateException("Audit publisher is closed"));
    return Stages.settle(
        Stages.call(() -> target.publish(event)),
        failure -> {
          unconfirmed.incrementAndGet();
          ConfirmedAuditPublisher.report(event, failure);
        });
  }

  public long unconfirmedCount() {
    return unconfirmed.get();
  }

  @Override
  public AsyncAuditEventPublisher delegate() {
    return target;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) PublisherClose.close(target);
  }
}
