package pe.axiz.core.audit;

/** Expone un delegado asíncrono nativo para el ciclo de vida y las métricas operativas. */
public interface AsyncAuditPublisherDecorator {
  AsyncAuditEventPublisher delegate();
}
