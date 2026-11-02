package pe.axiz.structure.hexagonal;

import java.util.Collection;
import org.reactivestreams.Publisher;
import pe.axiz.core.domain.DomainEvent;

/** Enqueues domain events inside the current reactive transaction. */
@FunctionalInterface
public interface ReactiveDomainEventSink {
  Publisher<Void> publish(Collection<? extends DomainEvent> events);
}
