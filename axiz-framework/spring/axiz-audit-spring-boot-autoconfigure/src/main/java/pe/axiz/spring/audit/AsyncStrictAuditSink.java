package pe.axiz.spring.audit;

import java.util.Objects;
import pe.axiz.core.audit.AsyncAuditEventPublisher;

/** Destino nativo durable; la etapa entregada solo se completa tras un ACK durable real. */
public record AsyncStrictAuditSink(String name, AsyncAuditEventPublisher publisher) {
  public AsyncStrictAuditSink {
    Objects.requireNonNull(name);
    Objects.requireNonNull(publisher);
    if (!name.matches("[A-Za-z0-9._-]{1,64}"))
      throw new IllegalArgumentException("Invalid strict audit sink identifier");
  }
}
