package pe.axiz.core.audit;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import pe.axiz.core.commons.Stages;

/**
 * Native asynchronous acknowledgement; audit delivery failure cannot revert a confirmed business.
 */
public final class ConfirmedAsyncAuditPublisher implements AsyncAuditEventPublisher {
  private final AsyncAuditEventPublisher target;
  private final AtomicLong unconfirmed = new AtomicLong();

  public ConfirmedAsyncAuditPublisher(AsyncAuditEventPublisher target) {
    this.target = Objects.requireNonNull(target);
  }

  @Override
  public CompletionStage<Void> publish(AuditEvent event) {
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
}
