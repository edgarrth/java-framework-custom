package pe.axiz.structure.hexagonal;

import java.util.List;
import pe.axiz.core.domain.DomainEvent;

/**
 * Where domain events go when a use case finishes. A transactional sink (outbox) receives them
 * inside the transaction; any other sink receives them after a successful commit.
 */
public interface DomainEventSink {

  void publish(List<DomainEvent> events);

  /** Whether {@link #publish} must run inside the use case transaction. */
  default boolean transactional() {
    return false;
  }

  static DomainEventSink discard() {
    return events -> {};
  }
}
