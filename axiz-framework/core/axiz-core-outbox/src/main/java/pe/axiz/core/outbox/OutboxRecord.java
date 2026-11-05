package pe.axiz.core.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.json.JsonWriter;
import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.core.messaging.EventEnvelope;

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
    @Nullable String lastError,
    long aggregateSequence,
    @Nullable String leaseOwner,
    @Nullable Instant leaseUntil) {

  public OutboxRecord {
    Objects.requireNonNull(id);
    Objects.requireNonNull(destination);
    Objects.requireNonNull(eventType);
    Objects.requireNonNull(envelopeJson);
    Objects.requireNonNull(status);
    Objects.requireNonNull(createdAt);
    Objects.requireNonNull(nextAttemptAt);
    if (aggregateType == null || aggregateId == null) {
      aggregateType = "axiz.event";
      aggregateId = id;
    }
  }

  public OutboxRecord(
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
    this(
        id,
        destination,
        aggregateType,
        aggregateId,
        eventType,
        envelopeJson,
        status,
        attempts,
        createdAt,
        nextAttemptAt,
        lastError,
        0,
        null,
        null);
  }

  /**
   * Metadata is reconstructed from the authoritative sequence assigned inside the write
   * transaction.
   */
  public EventEnvelope event() {
    return CloudEventJson.read(envelopeJson)
        .withExtension("axizseq", Long.toString(aggregateSequence))
        .withExtension(
            "axizaggregate",
            JsonWriter.write(
                Map.of(
                    "type",
                    Objects.requireNonNull(aggregateType),
                    "id",
                    Objects.requireNonNull(aggregateId))));
  }

  /** Lifecycle of an entry. */
  public enum Status {
    PENDING,
    IN_FLIGHT,
    PUBLISHED,
    FAILED,
    SKIPPED
  }
}
