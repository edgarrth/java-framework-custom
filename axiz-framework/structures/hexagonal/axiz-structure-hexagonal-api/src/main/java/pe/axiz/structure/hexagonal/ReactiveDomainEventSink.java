package pe.axiz.structure.hexagonal;

import java.util.Collection;
import org.reactivestreams.Publisher;
import pe.axiz.core.domain.DomainEvent;

/** Encola eventos de dominio dentro de la transacción reactiva actual. */
@FunctionalInterface
public interface ReactiveDomainEventSink {
  Publisher<Void> publish(Collection<? extends DomainEvent> events);
}
