package pe.axiz.core.messaging;

import java.util.List;

/** Delegates to the first publisher that supports the destination. */
public final class RoutingEventPublisher implements EventPublisher {

  private final List<EventPublisher> delegates;

  public RoutingEventPublisher(List<EventPublisher> delegates) {
    this.delegates = List.copyOf(delegates);
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
