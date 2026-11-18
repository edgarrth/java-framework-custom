package pe.axiz.core.audit;

import java.util.concurrent.CompletionStage;

/**
 * Native asynchronous delivery with the implementation's documented acceptance policy. A queued
 * publisher confirms admission to memory; a transport can confirm its broker's acknowledgement. Use
 * {@link AsyncStrictAuditPublisher} when durable acceptance is required. Submission never waits for
 * I/O. Cancellation should reach the transport where supported; it cannot retract a record already
 * accepted by a broker.
 */
@FunctionalInterface
public interface AsyncAuditEventPublisher {
  CompletionStage<Void> publish(AuditEvent event);
}
