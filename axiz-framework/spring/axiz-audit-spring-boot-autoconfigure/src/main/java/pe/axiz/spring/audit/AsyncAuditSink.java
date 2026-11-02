package pe.axiz.spring.audit;

import pe.axiz.core.audit.AsyncAuditEventPublisher;

/** Named native destination contributed by an adapter; it never adapts an imperative publisher. */
public record AsyncAuditSink(String name, AsyncAuditEventPublisher publisher) {}
