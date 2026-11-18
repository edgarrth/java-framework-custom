package pe.axiz.core.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.crypto.PayloadProtector;
import pe.axiz.core.json.JsonWriter;
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

  /**
   * Metadata is reconstructed from the authoritative sequence assigned inside the write
   * transaction.
   */
  public EventEnvelope event() {
    return event(PayloadProtector.NONE);
  }

  /** Reveals using authoritative row identity, before rebuilding broker sequence metadata. */
  public EventEnvelope event(PayloadProtector protector) {
    return CloudEventJson.read(protector.reveal(envelopeJson, "purpose:outbox:" + destination, id))
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

  @Override
  public String toString() {
    return "OutboxRecord[status=" + status + ", attempts=" + attempts + ", payload=****]";
  }

  /** Lifecycle of an entry. */
  public enum Status {
    PENDING,
    IN_FLIGHT,
    PUBLISHED,
    FAILED,
    SKIPPED
  }

  /** Named construction of a complete immutable stored snapshot. */
  public static Builder builder(
      String id, String destination, String eventType, String envelopeJson, Instant createdAt) {
    return new Builder(id, destination, eventType, envelopeJson, createdAt);
  }

  /** Mutable construction state; build validates and creates an independent immutable value. */
  public static final class Builder {
    private final String id;
    private final String destination;
    private final String eventType;
    private final String envelopeJson;
    private final Instant createdAt;
    private @Nullable String aggregateType = null;
    private @Nullable String aggregateId = null;
    private Status status = Status.PENDING;
    private int attempts = 0;
    private Instant nextAttemptAt = Instant.EPOCH;
    private @Nullable String lastError = null;
    private long aggregateSequence = 0;
    private @Nullable String leaseOwner = null;
    private @Nullable Instant leaseUntil = null;

    private Builder(
        String id, String destination, String eventType, String envelopeJson, Instant createdAt) {
      this.id = Objects.requireNonNull(id);
      this.destination = Objects.requireNonNull(destination);
      this.eventType = Objects.requireNonNull(eventType);
      this.envelopeJson = Objects.requireNonNull(envelopeJson);
      this.createdAt = Objects.requireNonNull(createdAt);
      nextAttemptAt = createdAt;
    }

    public Builder status(Status value) {
      this.status = value;
      return this;
    }

    public Builder attempts(int value) {
      this.attempts = value;
      return this;
    }

    public Builder nextAttemptAt(Instant value) {
      this.nextAttemptAt = value;
      return this;
    }

    public Builder lastError(@Nullable String value) {
      this.lastError = value;
      return this;
    }

    public Builder aggregateSequence(long value) {
      this.aggregateSequence = value;
      return this;
    }

    public Builder aggregate(@Nullable String type, @Nullable String id) {
      this.aggregateType = type;
      this.aggregateId = id;
      return this;
    }

    public Builder lease(@Nullable String owner, @Nullable Instant until) {
      this.leaseOwner = owner;
      this.leaseUntil = until;
      return this;
    }

    public OutboxRecord build() {
      return new OutboxRecord(
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
          aggregateSequence,
          leaseOwner,
          leaseUntil);
    }
  }
}
