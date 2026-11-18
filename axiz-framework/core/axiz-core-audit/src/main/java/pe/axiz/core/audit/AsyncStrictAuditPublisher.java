package pe.axiz.core.audit;

import java.util.concurrent.CompletionStage;

/**
 * Native confirmation of durable acceptance, including commit when using an outbox. Submission
 * never waits for I/O; failures propagate and never become a successful queue/log fallback.
 * Cancellation may prevent further work but cannot retract a completed durable write.
 */
@FunctionalInterface
public interface AsyncStrictAuditPublisher {
  CompletionStage<Void> publish(AuditEvent event);
}
