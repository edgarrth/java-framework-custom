package pe.axiz.core.audit;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import pe.axiz.core.concurrent.Stages;

/** CPU-only sealing before native submission; cancellation still reaches the delegate's stage. */
public final class SealingAsyncAuditPublisher
    implements AsyncAuditEventPublisher,
        AsyncAuditPublisherDecorator,
        AutoCloseable,
        AuditIntegrityMetrics {
  private final AuditSealer sealer;
  private final LongAdder sanitized = new LongAdder();
  private final AsyncAuditEventPublisher delegate;
  private final AtomicBoolean closed = new AtomicBoolean();

  public SealingAsyncAuditPublisher(AuditSealer sealer, AsyncAuditEventPublisher delegate) {
    this.sealer = Objects.requireNonNull(sealer);
    this.delegate = Objects.requireNonNull(delegate);
  }

  @Override
  public AsyncAuditEventPublisher delegate() {
    return delegate;
  }

  @Override
  public CompletionStage<Void> publish(AuditEvent event) {
    return Stages.call(
        () -> {
          if (closed.get()) throw new IllegalStateException("Audit publisher is closed");
          sanitized.add(event.detailsSanitized());
          return delegate.publish(sealer.seal(event));
        });
  }

  @Override
  public long detailsSanitizedCount() {
    return sanitized.sum();
  }

  @Override
  public Map<String, Long> chainSequences() {
    return Map.of("delivery", sealer.sequence());
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) PublisherClose.close(delegate);
  }
}
