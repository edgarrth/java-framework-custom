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
 * Entrada del outbox pendiente (o procesada).
 *
 * @param id id del evento
 * @param destination destino en el broker
 * @param aggregateType tipo de agregado, para diagnóstico
 * @param aggregateId id del agregado
 * @param eventType tipo de evento
 * @param envelopeJson JSON CloudEvents del evento
 * @param status estado
 * @param attempts intentos de publicación hasta el momento
 * @param createdAt momento de creación
 * @param nextAttemptAt momento a partir del cual el relay puede volver a intentar
 * @param lastError último error de publicación, truncado
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
   * Los metadatos se reconstruyen a partir de la secuencia autoritativa asignada dentro de la
   * transacción de escritura.
   */
  public EventEnvelope event() {
    return event(PayloadProtector.NONE);
  }

  /**
   * Revela usando la identidad autoritativa de la fila, antes de reconstruir los metadatos de
   * secuencia del broker.
   */
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

  /** Ciclo de vida de una entrada. */
  public enum Status {
    PENDING,
    IN_FLIGHT,
    PUBLISHED,
    FAILED,
    SKIPPED
  }

  /** Construcción nominal de una instantánea almacenada completa e inmutable. */
  public static Builder builder(
      String id, String destination, String eventType, String envelopeJson, Instant createdAt) {
    return new Builder(id, destination, eventType, envelopeJson, createdAt);
  }

  /** Estado mutable de construcción; build valida y crea un valor inmutable independiente. */
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
