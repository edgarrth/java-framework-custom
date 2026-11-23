package pe.axiz.core.audit;

/**
 * Puerto para publicar eventos de auditoría (log, MSK, SQS, etc.). Las implementaciones deben ser
 * thread-safe.
 */
@FunctionalInterface
public interface AuditPublisher {

  void publish(AuditEvent event);
}
