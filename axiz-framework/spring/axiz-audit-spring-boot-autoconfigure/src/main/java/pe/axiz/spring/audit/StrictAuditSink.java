package pe.axiz.spring.audit;

import java.util.Objects;
import pe.axiz.core.audit.AuditPublisher;

/**
 * Callback sin procesar que atestigua la aceptación durable del transporte; recibe un hecho
 * sellado. La cola y el log no califican.
 */
public record StrictAuditSink(String name, AuditPublisher publisher) {
  public StrictAuditSink {
    Objects.requireNonNull(name);
    Objects.requireNonNull(publisher);
    if (!name.matches("[A-Za-z0-9._-]{1,64}"))
      throw new IllegalArgumentException("Invalid strict audit sink identifier");
  }
}
