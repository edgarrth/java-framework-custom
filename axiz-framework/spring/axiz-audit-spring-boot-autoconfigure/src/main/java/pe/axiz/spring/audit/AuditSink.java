package pe.axiz.spring.audit;

import pe.axiz.core.audit.AuditPublisher;

/**
 * A destination of audit events contributed by an adapter auto-configuration. The audit starter
 * combines all sinks (or the log publisher when there is none) behind the async publisher.
 *
 * @param name sink name for diagnostics
 * @param publisher publisher
 */
public record AuditSink(String name, AuditPublisher publisher) {}
