package pe.axiz.core.audit;

/** Port to publish audit events (log, MSK, SQS...). Implementations must be thread-safe. */
@FunctionalInterface
public interface AuditPublisher {

  void publish(AuditEvent event);
}
