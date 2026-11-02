package pe.axiz.spring.audit;

import pe.axiz.core.audit.AsyncAuditEventPublisher;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.spring.foundation.ReactiveStages;
import reactor.core.publisher.Mono;

/**
 * Spring facade for native audit delivery. R2DBC implementations retain the subscriber transaction.
 */
@FunctionalInterface
public interface ReactiveAuditPublisher {
  Mono<Void> publish(AuditEvent event);

  static ReactiveAuditPublisher from(AsyncAuditEventPublisher publisher) {
    return event -> ReactiveStages.from(() -> publisher.publish(event)).then();
  }
}
