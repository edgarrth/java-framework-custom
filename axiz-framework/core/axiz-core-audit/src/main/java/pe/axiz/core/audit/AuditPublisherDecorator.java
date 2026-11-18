package pe.axiz.core.audit;

/** Exposes a wrapped publisher so lifecycle and operational metrics remain discoverable. */
public interface AuditPublisherDecorator {
  AuditPublisher delegate();
}
