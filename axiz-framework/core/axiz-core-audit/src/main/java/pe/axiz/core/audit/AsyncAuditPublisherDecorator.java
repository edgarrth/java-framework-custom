package pe.axiz.core.audit;

/** Exposes a native asynchronous delegate for lifecycle and operational metrics. */
public interface AsyncAuditPublisherDecorator {
  AsyncAuditEventPublisher delegate();
}
