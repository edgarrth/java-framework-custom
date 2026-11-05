package pe.axiz.core.audit;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.LoggerFactory;
import pe.axiz.core.commons.json.JsonWriter;

/** Destination acknowledgement, without claiming atomicity with the business transaction. */
public final class ConfirmedAuditPublisher implements AuditPublisher {
  private final AuditPublisher target;
  private final AtomicLong unconfirmed = new AtomicLong();

  public ConfirmedAuditPublisher(AuditPublisher target) {
    this.target = Objects.requireNonNull(target);
  }

  @Override
  public void publish(AuditEvent event) {
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

  static void report(AuditEvent event, Throwable failure) {
    LoggerFactory.getLogger("axiz.audit")
        .error(
            "audit.persistence=UNCONFIRMED {}",
            JsonWriter.write(event.withPersistence("UNCONFIRMED").toMap()),
            failure);
  }
}
