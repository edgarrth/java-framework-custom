package pe.axiz.spring.audit;

import pe.axiz.core.audit.AsyncAuditEventPublisher;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.spring.foundation.ReactiveStages;
import reactor.core.publisher.Mono;

/**
 * Fachada de Spring para la entrega nativa de auditoría. Las implementaciones R2DBC conservan la
 * transacción del suscriptor.
 */
@FunctionalInterface
public interface ReactiveAuditPublisher {
  Mono<Void> publish(AuditEvent event);

  static ReactiveAuditPublisher from(AsyncAuditEventPublisher publisher) {
    return event -> ReactiveStages.from(() -> publisher.publish(event)).then();
  }
}
