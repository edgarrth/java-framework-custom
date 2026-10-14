package pe.axiz.core.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregate root: consistency boundary that records domain events while it changes. The application
 * layer pulls the events after saving and publishes them once the transaction commits.
 *
 * @param <I> id type
 */
public abstract class AggregateRoot<I> extends Entity<I> {

  private final List<DomainEvent> pendingEvents = new ArrayList<>();

  protected void registerEvent(DomainEvent event) {
    pendingEvents.add(event);
  }

  /** Events registered since the last pull, without removing them. */
  public List<DomainEvent> peekEvents() {
    return List.copyOf(pendingEvents);
  }

  /** Returns and clears the pending events. */
  public List<DomainEvent> pullEvents() {
    List<DomainEvent> events = List.copyOf(pendingEvents);
    pendingEvents.clear();
    return events;
  }
}
