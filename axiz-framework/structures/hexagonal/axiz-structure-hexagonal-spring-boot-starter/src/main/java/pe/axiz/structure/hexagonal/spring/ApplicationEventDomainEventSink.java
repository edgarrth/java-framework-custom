package pe.axiz.structure.hexagonal.spring;

import java.util.List;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import pe.axiz.core.domain.DomainEvent;
import pe.axiz.structure.hexagonal.DomainEventSink;

/**
 * In-process publication after commit through Spring application events, used when the service has
 * no outbox (events stay inside the service).
 */
public class ApplicationEventDomainEventSink implements DomainEventSink {

  private final ApplicationEventPublisher publisher;

  public ApplicationEventDomainEventSink(ApplicationEventPublisher publisher) {
    this.publisher = Objects.requireNonNull(publisher);
  }

  @Override
  public void publish(List<DomainEvent> events) {
    events.forEach(publisher::publishEvent);
  }
}
