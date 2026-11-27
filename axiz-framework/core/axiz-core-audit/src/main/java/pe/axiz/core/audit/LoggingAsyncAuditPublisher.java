package pe.axiz.core.audit;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.json.JsonWriter;

/**
 * Entrega eventos de auditoría JSON a un transporte de logging no bloqueante. Requiere un appender
 * no bloqueante; la finalización confirma la entrega al log, no el almacenamiento durable. Para
 * evidencia financiera se usa un outbox.
 */
public final class LoggingAsyncAuditPublisher implements AsyncAuditEventPublisher {
  private static final Logger LOG = LoggerFactory.getLogger("axiz.audit");

  @Override
  public CompletionStage<Void> publish(AuditEvent event) {
    return Stages.call(
        () -> {
          if (LOG.isInfoEnabled()) LOG.info(JsonWriter.writeCanonical(event.toMap()));
          return CompletableFuture.completedFuture(null);
        });
  }
}
