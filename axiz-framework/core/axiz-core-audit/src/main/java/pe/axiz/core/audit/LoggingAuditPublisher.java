package pe.axiz.core.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.axiz.core.json.JsonWriter;

/** Escribe eventos de auditoría como líneas JSON en el logger {@code axiz.audit}. */
public final class LoggingAuditPublisher implements AuditPublisher {

  private static final Logger LOG = LoggerFactory.getLogger("axiz.audit");

  @Override
  public void publish(AuditEvent event) {
    if (LOG.isInfoEnabled()) {
      LOG.info(JsonWriter.writeCanonical(event.toMap()));
    }
  }
}
