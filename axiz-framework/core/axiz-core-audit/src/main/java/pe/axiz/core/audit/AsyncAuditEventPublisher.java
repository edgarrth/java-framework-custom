package pe.axiz.core.audit;

import java.util.concurrent.CompletionStage;

/**
 * Native asynchronous delivery; completion means acceptance by the destination. Implementations
 * must not wait for I/O on submission and cancellation must reach the native transport.
 */
@FunctionalInterface
public interface AsyncAuditEventPublisher {
  CompletionStage<Void> publish(AuditEvent event);
}
