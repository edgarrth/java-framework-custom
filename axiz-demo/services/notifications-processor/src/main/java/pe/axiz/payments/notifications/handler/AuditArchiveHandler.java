package pe.axiz.payments.notifications.handler;

import org.springframework.stereotype.Component;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.HandlesEvent;
import pe.axiz.core.messaging.TypedEventHandler;
import pe.axiz.payments.notifications.archive.AuditArchive;
import pe.axiz.payments.notifications.event.AuditRecord;

/**
 * Archives the audit trail the services publish to the SQS audit queue ({@code
 * pe.axiz.audit.event.v1}). A failure leaves the message in the queue: it is retried and finally
 * moved to the dead-letter queue, never lost.
 */
@Component
@HandlesEvent("pe.axiz.audit.event.v1")
public class AuditArchiveHandler extends TypedEventHandler<AuditRecord> {

  private final AuditArchive archive;

  public AuditArchiveHandler(AuditArchive archive) {
    this.archive = archive;
  }

  @Override
  protected void on(AuditRecord record, EventEnvelope envelope) {
    archive.store(record);
  }
}
