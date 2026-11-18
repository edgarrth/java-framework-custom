package pe.axiz.core.audit;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/** Seals before admission. Owns and closes its delegate exactly once. */
public final class SealingAuditPublisher
    implements AuditPublisher, AuditPublisherDecorator, AutoCloseable, AuditIntegrityMetrics {
  private final AuditSealer sealer;
  private final LongAdder sanitized = new LongAdder();
  private final AuditPublisher delegate;
  private final AtomicBoolean closed = new AtomicBoolean();

  public SealingAuditPublisher(AuditSealer sealer, AuditPublisher delegate) {
    this.sealer = Objects.requireNonNull(sealer);
    this.delegate = Objects.requireNonNull(delegate);
  }

  @Override
  public AuditPublisher delegate() {
    return delegate;
  }

  @Override
  public void publish(AuditEvent event) {
    if (closed.get()) throw new IllegalStateException("Audit publisher is closed");
    sanitized.add(event.detailsSanitized());
    delegate.publish(sealer.seal(event));
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
