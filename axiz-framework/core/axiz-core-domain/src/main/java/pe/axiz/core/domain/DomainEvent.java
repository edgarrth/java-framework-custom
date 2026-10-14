package pe.axiz.core.domain;

import java.time.Instant;

/**
 * Something that happened in the domain. Events are immutable (normally records) and are published
 * only after the transaction that produced them commits.
 */
public interface DomainEvent {

  /** Unique id of this event (UUIDv7); used for deduplication downstream. */
  String eventId();

  /** When it happened. */
  Instant occurredAt();

  /** Id of the aggregate that produced it. */
  String aggregateId();

  /** Event type, by default the simple class name in kebab-case, e.g. {@code order-placed}. */
  default String eventType() {
    return EventTypes.kebab(getClass().getSimpleName());
  }
}
