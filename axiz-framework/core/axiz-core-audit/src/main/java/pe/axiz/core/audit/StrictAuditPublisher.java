package pe.axiz.core.audit;

/**
 * Confirms durable acceptance before returning. Failure must propagate; an in-memory queue or a log
 * write cannot satisfy this contract. Implementations seal the final immutable fact and use a
 * finite confirmation deadline. Failure or interruption does not prove that nothing was stored.
 */
@FunctionalInterface
public interface StrictAuditPublisher {
  void publish(AuditEvent event);
}
