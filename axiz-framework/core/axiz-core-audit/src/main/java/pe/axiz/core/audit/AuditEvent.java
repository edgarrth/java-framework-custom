package pe.axiz.core.audit;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.TransactionOutcome;

/**
 * Immutable audit record of who did what, on which resource, when and with what result. Details are
 * sanitized for the audit channel before the event is built.
 *
 * @param id unique fact identifier (for example UUIDv7 or an independent random UUIDv4)
 * @param occurredAt when it happened
 * @param service service that produced it
 * @param action action, e.g. {@code order.create}
 * @param resourceType resource type, e.g. {@code order}
 * @param resourceId resource id, if known
 * @param actor who performed it
 * @param outcome result
 * @param errorCode error code when it failed
 * @param correlationId correlation id
 * @param channel origin channel
 * @param tenant tenant
 * @param details sanitized details
 */
public record AuditEvent(
    String id,
    Instant occurredAt,
    String service,
    String action,
    String resourceType,
    @Nullable String resourceId,
    Actor actor,
    Outcome outcome,
    @Nullable String errorCode,
    @Nullable String correlationId,
    @Nullable String channel,
    @Nullable String tenant,
    Map<String, @Nullable Object> details,
    TransactionOutcome transactionOutcome,
    TransactionOutcome operationOutcome,
    @Nullable String persistence,
    @Nullable AuditIntegrity integrity,
    int detailsSanitized) {

  // An explicit canonical constructor, not a compact one: javac does not write the @Nullable of a
  // type argument on the parameters of a compact constructor, so callers in other modules would
  // see Map<String, Object> and NullAway would reject details with null values.
  public AuditEvent(
      String id,
      Instant occurredAt,
      String service,
      String action,
      String resourceType,
      @Nullable String resourceId,
      Actor actor,
      Outcome outcome,
      @Nullable String errorCode,
      @Nullable String correlationId,
      @Nullable String channel,
      @Nullable String tenant,
      Map<String, @Nullable Object> details,
      TransactionOutcome transactionOutcome,
      TransactionOutcome operationOutcome,
      @Nullable String persistence,
      @Nullable AuditIntegrity integrity,
      int detailsSanitized) {
    this.transactionOutcome = Objects.requireNonNull(transactionOutcome);
    this.operationOutcome = Objects.requireNonNull(operationOutcome);
    this.id = Objects.requireNonNull(id);
    this.occurredAt = Objects.requireNonNull(occurredAt);
    this.service = Objects.requireNonNull(service);
    this.action = Objects.requireNonNull(action);
    this.resourceType = Objects.requireNonNull(resourceType);
    this.resourceId = resourceId;
    this.actor = Objects.requireNonNull(actor);
    this.outcome = Objects.requireNonNull(outcome);
    this.errorCode = errorCode;
    this.correlationId = correlationId;
    this.channel = channel;
    this.tenant = tenant;
    if (detailsSanitized < 0)
      throw new IllegalArgumentException("Sanitized count must not be negative");
    if (integrity != null && integrity.sequence() < 1)
      throw new IllegalArgumentException("An event requires a positive seal sequence");
    AuditDetails.Frozen frozen = AuditDetails.freeze(details);
    this.details = frozen.details();
    this.detailsSanitized = Math.addExact(detailsSanitized, frozen.sanitized());
    this.persistence = persistence;
    this.integrity = integrity;
  }

  public AuditEvent withOutcomes(TransactionOutcome local, TransactionOutcome operation) {
    if (integrity != null) throw new IllegalStateException("A sealed event cannot change outcomes");
    return AuditEvent.builder()
        .id(id)
        .occurredAt(occurredAt)
        .service(service)
        .action(action)
        .resourceType(resourceType)
        .resourceId(resourceId)
        .actor(actor)
        .outcome(outcome)
        .errorCode(errorCode)
        .correlationId(correlationId)
        .channel(channel)
        .tenant(tenant)
        .details(details)
        .transactionOutcome(local)
        .operationOutcome(operation)
        .persistence(persistence)
        .integrity(null)
        .detailsSanitized(detailsSanitized)
        .build();
  }

  /** Deeply frozen snapshot shared by all transport representations of the same fact. */
  @Override
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "The constructor recursively freezes every value with AuditDetails.freeze; no"
              + " caller-owned mutable value survives. AuditSnapshotTest verifies all mutation"
              + " paths.")
  public Map<String, @Nullable Object> details() {
    return details;
  }

  public AuditEvent withPersistence(String status) {
    return AuditEvent.builder()
        .id(id)
        .occurredAt(occurredAt)
        .service(service)
        .action(action)
        .resourceType(resourceType)
        .resourceId(resourceId)
        .actor(actor)
        .outcome(outcome)
        .errorCode(errorCode)
        .correlationId(correlationId)
        .channel(channel)
        .tenant(tenant)
        .details(details)
        .transactionOutcome(transactionOutcome)
        .operationOutcome(operationOutcome)
        .persistence(Objects.requireNonNull(status))
        .integrity(integrity)
        .detailsSanitized(detailsSanitized)
        .build();
  }

  /** Attaches the calculated seal without changing the original snapshot. */
  public AuditEvent withIntegrity(AuditIntegrity seal) {
    if (integrity != null) throw new IllegalStateException("An event is already sealed");
    return AuditEvent.builder()
        .id(id)
        .occurredAt(occurredAt)
        .service(service)
        .action(action)
        .resourceType(resourceType)
        .resourceId(resourceId)
        .actor(actor)
        .outcome(outcome)
        .errorCode(errorCode)
        .correlationId(correlationId)
        .channel(channel)
        .tenant(tenant)
        .details(details)
        .transactionOutcome(transactionOutcome)
        .operationOutcome(operationOutcome)
        .persistence(persistence)
        .integrity(Objects.requireNonNull(seal))
        .detailsSanitized(detailsSanitized)
        .build();
  }

  /** CloudEvents type of audit events on every transport (the action travels in the data). */
  public static final String CLOUD_EVENT_TYPE = "pe.axiz.audit.event.v1";

  /**
   * Transport deduplication identity: retransmissions of one attempt share it; different sealed
   * attempts of the same fact do not. The fact identity remains id() in the audit payload.
   */
  public String deliveryId() {
    if (integrity == null) return id;
    // '#' cannot occur in a validated chain id. Sequence and hash have unambiguous formats.
    String identity =
        "axiz.audit.attempt.v1#"
            + integrity.chainId()
            + "#"
            + integrity.sequence()
            + "#"
            + integrity.hash();
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(identity.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("Audit identity algorithm unavailable", unavailable);
    }
  }

  /**
   * CloudEvents 1.0 context attributes of this event ({@code specversion}, {@code id}, {@code
   * source}, {@code type}, {@code time}, {@code subject} and the {@code correlationid} extension
   * when known), so publishers write proper CloudEvents in structured or binary mode.
   */
  public Map<String, String> cloudEventAttributes() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("specversion", "1.0");
    m.put("id", deliveryId());
    m.put("source", "urn:axiz:" + service);
    m.put("type", CLOUD_EVENT_TYPE);
    m.put("time", occurredAt.toString());
    m.put("subject", resourceType + ":" + (resourceId == null ? "none" : resourceId));
    if (correlationId != null) {
      m.put("correlationid", correlationId);
    }
    if (integrity != null) {
      m.put("axizchain", integrity.chainId());
      m.put("axizseq", Long.toString(integrity.sequence()));
      m.put("axizhash", integrity.hash());
    }
    return Collections.unmodifiableMap(m);
  }

  /** Result of the audited action. */
  public enum Outcome {
    SUCCESS,
    FAILURE
  }

  /**
   * Who acted.
   *
   * @param subject end user, if any
   * @param clientId calling application
   * @param ip client address, if known
   */
  public record Actor(@Nullable String subject, @Nullable String clientId, @Nullable String ip) {
    public static Actor system() {
      return new Actor(null, "system", null);
    }
  }

  /** JSON-friendly map (ISO-8601 instant, enum names). */
  public Map<String, @Nullable Object> toMap() {
    Map<String, @Nullable Object> m = new LinkedHashMap<>();
    m.put("id", id);
    m.put("occurredAt", occurredAt.toString());
    m.put("service", service);
    m.put("action", action);
    m.put("resourceType", resourceType);
    m.put("resourceId", resourceId);
    Map<String, @Nullable Object> a = new LinkedHashMap<>();
    a.put("subject", actor.subject());
    a.put("clientId", actor.clientId());
    a.put("ip", actor.ip());
    m.put("actor", Collections.unmodifiableMap(a));
    m.put("outcome", outcome.name());
    m.put("transactionOutcome", transactionOutcome.name());
    m.put("operationOutcome", operationOutcome.name());
    m.put("errorCode", errorCode);
    m.put("correlationId", correlationId);
    m.put("channel", channel);
    m.put("tenant", tenant);
    m.put("details", details);
    m.put("detailsSanitized", detailsSanitized);
    if (persistence != null) m.put("persistence", persistence);
    if (integrity != null) {
      Map<String, @Nullable Object> seal = new LinkedHashMap<>();
      seal.put("chainId", integrity.chainId());
      seal.put("sequence", integrity.sequence());
      seal.put("previousHash", integrity.previousHash());
      seal.put("hash", integrity.hash());
      seal.put("keyId", integrity.keyId());
      AttemptRef previous = integrity.previousAttempt();
      if (previous != null)
        seal.put(
            "previousAttempt",
            Map.of(
                "chainId",
                previous.chainId(),
                "sequence",
                previous.sequence(),
                "hash",
                previous.hash(),
                "outcome",
                previous.outcome().name()));
      m.put("integrity", Collections.unmodifiableMap(seal));
    }
    return Collections.unmodifiableMap(m);
  }

  /** Named construction; required values and invariants are checked by build. */
  public static Builder builder() {
    return new Builder();
  }

  /** Mutable construction state; use one builder per operation, never as shared state. */
  public static final class Builder {
    private @Nullable String id = null;
    private @Nullable Instant occurredAt = null;
    private @Nullable String service = null;
    private @Nullable String action = null;
    private @Nullable String resourceType = null;
    private @Nullable String resourceId = null;
    private @Nullable Actor actor = null;
    private @Nullable Outcome outcome = null;
    private @Nullable String errorCode = null;
    private @Nullable String correlationId = null;
    private @Nullable String channel = null;
    private @Nullable String tenant = null;
    private @Nullable Map<String, @Nullable Object> details = Map.of();
    private @Nullable TransactionOutcome transactionOutcome = null;
    private @Nullable TransactionOutcome operationOutcome = null;
    private @Nullable String persistence = null;
    private @Nullable AuditIntegrity integrity = null;
    private int detailsSanitized = 0;

    private Builder() {}

    public Builder id(String value) {
      this.id = value;
      return this;
    }

    public Builder occurredAt(Instant value) {
      this.occurredAt = value;
      return this;
    }

    public Builder service(String value) {
      this.service = value;
      return this;
    }

    public Builder action(String value) {
      this.action = value;
      return this;
    }

    public Builder resourceType(String value) {
      this.resourceType = value;
      return this;
    }

    public Builder resourceId(@Nullable String value) {
      this.resourceId = value;
      return this;
    }

    public Builder actor(Actor value) {
      this.actor = value;
      return this;
    }

    public Builder outcome(Outcome value) {
      this.outcome = value;
      return this;
    }

    public Builder errorCode(@Nullable String value) {
      this.errorCode = value;
      return this;
    }

    public Builder correlationId(@Nullable String value) {
      this.correlationId = value;
      return this;
    }

    public Builder channel(@Nullable String value) {
      this.channel = value;
      return this;
    }

    public Builder tenant(@Nullable String value) {
      this.tenant = value;
      return this;
    }

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification =
            "Construction state only. AuditDetails.freeze in the canonical constructor owns the "
                + "bounded deep snapshot, preserves already frozen identity and accounts for null, "
                + "cyclic and unsupported values. A shallow copy would change that contract.")
    public Builder details(Map<String, @Nullable Object> value) {
      this.details = value;
      return this;
    }

    public Builder transactionOutcome(TransactionOutcome value) {
      this.transactionOutcome = value;
      return this;
    }

    public Builder operationOutcome(TransactionOutcome value) {
      this.operationOutcome = value;
      return this;
    }

    public Builder persistence(@Nullable String value) {
      this.persistence = value;
      return this;
    }

    public Builder integrity(@Nullable AuditIntegrity value) {
      this.integrity = value;
      return this;
    }

    public Builder detailsSanitized(int value) {
      this.detailsSanitized = value;
      return this;
    }

    public AuditEvent build() {
      return new AuditEvent(
          Objects.requireNonNull(id, "id"),
          Objects.requireNonNull(occurredAt, "occurredAt"),
          Objects.requireNonNull(service, "service"),
          Objects.requireNonNull(action, "action"),
          Objects.requireNonNull(resourceType, "resourceType"),
          resourceId,
          Objects.requireNonNull(actor, "actor"),
          Objects.requireNonNull(outcome, "outcome"),
          errorCode,
          correlationId,
          channel,
          tenant,
          Objects.requireNonNull(details, "details"),
          Objects.requireNonNull(transactionOutcome, "transactionOutcome"),
          Objects.requireNonNull(operationOutcome, "operationOutcome"),
          persistence,
          integrity,
          detailsSanitized);
    }
  }
}
