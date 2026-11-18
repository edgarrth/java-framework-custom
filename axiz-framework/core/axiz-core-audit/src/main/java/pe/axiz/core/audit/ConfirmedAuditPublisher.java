package pe.axiz.core.audit;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.LoggerFactory;
import pe.axiz.core.json.JsonWriter;

/** Destination acknowledgement, without claiming atomicity with the business transaction. */
public final class ConfirmedAuditPublisher
    implements AuditPublisher, AuditPublisherDecorator, AutoCloseable {
  private final AuditPublisher target;
  private final AtomicLong unconfirmed = new AtomicLong();
  private final AtomicBoolean closed = new AtomicBoolean();

  public ConfirmedAuditPublisher(AuditPublisher target) {
    this.target = Objects.requireNonNull(target);
  }

  @Override
  public void publish(AuditEvent event) {
    if (closed.get()) throw new IllegalStateException("Audit publisher is closed");
    try {
      target.publish(event);
    } catch (RuntimeException failure) {
      unconfirmed.incrementAndGet();
      report(event, failure);
    }
  }

  public long unconfirmedCount() {
    return unconfirmed.get();
  }

  @Override
  public AuditPublisher delegate() {
    return target;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) PublisherClose.close(target);
  }

  static void report(AuditEvent event, Throwable failure) {
    LoggerFactory.getLogger("axiz.audit")
        .error(
            "audit.persistence=NOT_CONFIRMED {} failureType={}",
            JsonWriter.writeCanonical(
                event
                    .withPersistence(
                        "UNCERTAIN".equals(event.persistence()) ? "UNCERTAIN" : "NOT_CONFIRMED")
                    .toMap()),
            failure.getClass().getName());
  }
}
