package pe.axiz.core.audit;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.TransactionOutcome;

/**
 * Immutable audit record of who did what, on which resource, when and with what result. Details are
 * sanitized for the audit channel before the event is built.
 *
 * @param id unique id (UUIDv7)
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
    TransactionOutcome operationOutcome) {

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
      TransactionOutcome operationOutcome) {
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
    this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
  }

  /** Compatibility constructor; callers without transaction evidence use UNKNOWN on both axes. */
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
      Map<String, @Nullable Object> details) {
    this(
        id,
        occurredAt,
        service,
        action,
        resourceType,
        resourceId,
        actor,
        outcome,
        errorCode,
        correlationId,
        channel,
        tenant,
        details,
        TransactionOutcome.UNKNOWN,
        TransactionOutcome.UNKNOWN);
  }

  public AuditEvent withOutcomes(TransactionOutcome local, TransactionOutcome operation) {
    return new AuditEvent(
        id,
        occurredAt,
        service,
        action,
        resourceType,
        resourceId,
        actor,
        outcome,
        errorCode,
        correlationId,
        channel,
        tenant,
        details,
        local,
        operation);
  }

  public AuditEvent withPersistence(String status) {
    Map<String, @Nullable Object> marked = new LinkedHashMap<>(details);
    marked.put("audit.persistence", status);
    return new AuditEvent(
        id,
        occurredAt,
        service,
        action,
        resourceType,
        resourceId,
        actor,
        outcome,
        errorCode,
        correlationId,
        channel,
        tenant,
        marked,
        transactionOutcome,
        operationOutcome);
  }

  /** CloudEvents type of audit events on every transport (the action travels in the data). */
  public static final String CLOUD_EVENT_TYPE = "pe.axiz.audit.event.v1";

  /**
   * CloudEvents 1.0 context attributes of this event ({@code specversion}, {@code id}, {@code
   * source}, {@code type}, {@code time}, {@code subject} and the {@code correlationid} extension
   * when known), so publishers write proper CloudEvents in structured or binary mode.
   */
  public Map<String, String> cloudEventAttributes() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("specversion", "1.0");
    m.put("id", id);
    m.put("source", "urn:axiz:" + service);
    m.put("type", CLOUD_EVENT_TYPE);
    m.put("time", occurredAt.toString());
    m.put("subject", resourceType + ":" + (resourceId == null ? "none" : resourceId));
    if (correlationId != null) {
      m.put("correlationid", correlationId);
    }
    return m;
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
    m.put("actor", a);
    m.put("outcome", outcome.name());
    m.put("transactionOutcome", transactionOutcome.name());
    m.put("operationOutcome", operationOutcome.name());
    m.put("errorCode", errorCode);
    m.put("correlationId", correlationId);
    m.put("channel", channel);
    m.put("tenant", tenant);
    m.put("details", details);
    return m;
  }
}
