package pe.axiz.core.audit;

import java.util.concurrent.CompletionStage;

/**
 * Native asynchronous delivery; completion means acceptance by the destination. Implementations
 * must not wait for I/O on submission. Cancellation should reach the transport where supported; it
 * cannot retract a record already accepted by a broker.
 */
@FunctionalInterface
public interface AsyncAuditEventPublisher {
  CompletionStage<Void> publish(AuditEvent event);
}
