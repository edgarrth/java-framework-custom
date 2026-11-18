package pe.axiz.spring.audit;

import java.util.Objects;
import pe.axiz.core.audit.AuditPublisher;

/**
 * Raw callback attesting durable transport acceptance; receives a sealed fact. Queue/log do not
 * qualify.
 */
public record StrictAuditSink(String name, AuditPublisher publisher) {
  public StrictAuditSink {
    Objects.requireNonNull(name);
    Objects.requireNonNull(publisher);
    if (!name.matches("[A-Za-z0-9._-]{1,64}"))
      throw new IllegalArgumentException("Invalid strict audit sink identifier");
  }
}
