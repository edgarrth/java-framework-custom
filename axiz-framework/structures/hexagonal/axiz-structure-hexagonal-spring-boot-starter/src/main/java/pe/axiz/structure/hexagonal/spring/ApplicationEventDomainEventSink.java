package pe.axiz.structure.hexagonal.spring;

import java.util.List;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import pe.axiz.core.domain.DomainEvent;
import pe.axiz.structure.hexagonal.DomainEventSink;

/**
 * Publicación en proceso tras el commit mediante eventos de aplicación de Spring, usada cuando el
 * servicio no tiene outbox (los eventos permanecen dentro del servicio).
 */
public final class ApplicationEventDomainEventSink implements DomainEventSink {

  private final ApplicationEventPublisher publisher;

  public ApplicationEventDomainEventSink(ApplicationEventPublisher publisher) {
    this.publisher = Objects.requireNonNull(publisher);
  }

  @Override
  public void publish(List<DomainEvent> events) {
    events.forEach(publisher::publishEvent);
  }
}
