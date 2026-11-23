package pe.axiz.core.messaging;

import java.util.List;

/** Delega en el primer publicador que soporta el destino. */
public final class RoutingEventPublisher implements EventPublisher, BoundedPublisher {

  private final List<EventPublisher> delegates;

  public RoutingEventPublisher(List<EventPublisher> delegates) {
    this.delegates = List.copyOf(delegates);
  }

  @Override
  public PublishBound publishBound() {
    return delegates.stream()
        .map(BoundedPublisher::of)
        .reduce(PublishBound::merge)
        .orElseGet(PublishBound::unbounded);
  }

  @Override
  public PublishResult publish(String destination, EventEnvelope event) {
    for (EventPublisher p : delegates) {
      if (p.supports(destination)) {
        return p.publish(destination, event);
      }
    }
    throw new IllegalArgumentException("No publisher supports destination " + destination);
  }

  @Override
  public boolean supports(String destination) {
    return delegates.stream().anyMatch(p -> p.supports(destination));
  }
}
