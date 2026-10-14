package pe.axiz.core.outbox;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A pending (or processed) outbox entry.
 *
 * @param id event id
 * @param destination broker destination
 * @param aggregateType aggregate type, for diagnostics
 * @param aggregateId aggregate id
 * @param eventType event type
 * @param envelopeJson CloudEvents JSON of the event
 * @param status status
 * @param attempts publication attempts so far
 * @param createdAt creation time
 * @param nextAttemptAt when the relay may try again
 * @param lastError last publication error, truncated
 */
public record OutboxRecord(
    String id,
    String destination,
    @Nullable String aggregateType,
    @Nullable String aggregateId,
    String eventType,
    String envelopeJson,
    Status status,
    int attempts,
    Instant createdAt,
    Instant nextAttemptAt,
    @Nullable String lastError) {

  public OutboxRecord {
    Objects.requireNonNull(id);
    Objects.requireNonNull(destination);
    Objects.requireNonNull(eventType);
    Objects.requireNonNull(envelopeJson);
    Objects.requireNonNull(status);
    Objects.requireNonNull(createdAt);
    Objects.requireNonNull(nextAttemptAt);
  }

  /** Lifecycle of an entry. */
  public enum Status {
    PENDING,
    PUBLISHED,
    FAILED
  }
}
