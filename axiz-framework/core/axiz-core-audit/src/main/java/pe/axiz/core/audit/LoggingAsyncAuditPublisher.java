package pe.axiz.core.audit;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.json.JsonWriter;

/**
 * Hands JSON audit events to a non-blocking logging transport. Requires a non-blocking appender;
 * completion acknowledges log hand-off, not durable storage. Use an outbox for financial evidence.
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
