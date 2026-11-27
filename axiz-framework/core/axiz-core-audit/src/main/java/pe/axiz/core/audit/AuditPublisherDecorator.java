package pe.axiz.core.audit;

/**
 * Expone un publicador envuelto para que el ciclo de vida y las métricas operativas sigan siendo
 * descubribles.
 */
public interface AuditPublisherDecorator {
  AuditPublisher delegate();
}
