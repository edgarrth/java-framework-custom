package pe.axiz.spring.audit;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditPublishException;
import pe.axiz.core.audit.AuditPublisher;
import pe.axiz.core.commons.json.JsonWriter;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.outbox.OutboxWriter;

/**
 * Durable audit: a successful action recorded inside a transaction is written to the outbox in that
 * same transaction, so the evidence exists if and only if the business change was committed, and
 * survives a crash right after the commit (the relay publishes it later, at least once, with the
 * event id for deduplication). The event keeps the CloudEvents attributes of the other audit
 * transports, so consumers see the same format.
 *
 * <p>Events that cannot be tied to a commit go to the fallback (the asynchronous publisher): those
 * recorded outside a transaction, unless {@code outside-transaction=fail}, and failures, whose
 * transaction is about to roll back and would take the audit record with it.
 */
public class TransactionalAuditPublisher implements AuditPublisher, AutoCloseable {

  private final OutboxWriter outbox;
  private final String destination;
  private final AuditPublisher fallback;
  private final AuditOutsideTransaction outside;

  public TransactionalAuditPublisher(
      OutboxWriter outbox,
      String destination,
      AuditPublisher fallback,
      AuditOutsideTransaction outside) {
    this.outbox = Objects.requireNonNull(outbox);
    this.destination = Objects.requireNonNull(destination);
    this.fallback = Objects.requireNonNull(fallback);
    this.outside = Objects.requireNonNull(outside);
  }

  @Override
  public void publish(AuditEvent event) {
    if (event.outcome() == AuditEvent.Outcome.FAILURE) {
      fallback.publish(event); // the transaction is rolling back
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      outbox.write(destination, envelope(event), "audit");
      return;
    }
    if (outside == AuditOutsideTransaction.FAIL) {
      throw new AuditPublishException(
          "Audit event "
              + event.id()
              + " ("
              + event.action()
              + ") recorded outside a transaction (axiz.audit.durable.outside-transaction=fail)",
          new IllegalStateException("no active transaction"));
    }
    fallback.publish(event);
  }

  public AuditPublisher fallback() {
    return fallback;
  }

  static EventEnvelope envelope(AuditEvent event) {
    Map<String, String> ce = event.cloudEventAttributes();
    Map<String, String> extensions = new LinkedHashMap<>();
    if (event.correlationId() != null) {
      extensions.put(EventEnvelope.CORRELATION_ID, event.correlationId());
    }
    return new EventEnvelope(
        event.id(),
        Objects.requireNonNull(ce.get("source")),
        AuditEvent.CLOUD_EVENT_TYPE,
        event.occurredAt(),
        ce.get("subject"),
        "application/json",
        JsonWriter.write(event.toMap()).getBytes(StandardCharsets.UTF_8),
        extensions);
  }

  @Override
  public void close() throws Exception {
    if (fallback instanceof AutoCloseable closeable) {
      closeable.close();
    }
  }
}
