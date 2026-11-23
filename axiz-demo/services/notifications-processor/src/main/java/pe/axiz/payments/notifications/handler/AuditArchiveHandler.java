package pe.axiz.payments.notifications.handler;

import org.springframework.stereotype.Component;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.HandlesEvent;
import pe.axiz.core.messaging.TypedEventHandler;
import pe.axiz.payments.notifications.event.AuditRecord;
import pe.axiz.payments.notifications.repository.archive.AuditArchive;

/**
 * Archiva el rastro de auditoría que los servicios publican en la cola SQS de auditoría ({@code
 * pe.axiz.audit.event.v1}). Una falla deja el mensaje en la cola: se reintenta y finalmente se
 * mueve a la cola de mensajes fallidos (dead-letter), sin perderse nunca.
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
