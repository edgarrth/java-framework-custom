package pe.axiz.spring.audit;

import pe.axiz.core.audit.AsyncAuditEventPublisher;

/** Destino nativo con nombre aportado por un adaptador; nunca adapta un publicador imperativo. */
public record AsyncAuditSink(String name, AsyncAuditEventPublisher publisher) {}
