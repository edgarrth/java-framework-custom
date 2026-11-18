package pe.axiz.core.audit;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.TransactionOutcome;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.sensitive.ObjectMasker;
import pe.axiz.core.sensitive.OutputChannel;

/**
 * Builds sanitized immutable snapshots without publishing or performing I/O. Instances belong to
 * one recording operation; do not share a mutable record across concurrent subscribers.
 */
public final class AuditRecord {
  private final String service;
  private final ObjectMasker masker;
  private final IdGenerator ids;
  private final Clock clock;
  private final String action;
  private final String resourceType;
  private @Nullable String resourceId;
  private AuditTrail.Context context = AuditTrail.Context.system();
  private TransactionOutcome local = TransactionOutcome.UNKNOWN;
  private TransactionOutcome operation = TransactionOutcome.UNKNOWN;
  private int sanitized;
  private final Map<String, @Nullable Object> details = new LinkedHashMap<>();

  public AuditRecord(
      String service,
      ObjectMasker masker,
      IdGenerator ids,
      Clock clock,
      String action,
      String resourceType) {
    this.service = service;
    this.masker = masker;
    this.ids = ids;
    this.clock = clock;
    this.action = Objects.requireNonNull(action);
    this.resourceType = Objects.requireNonNull(resourceType);
  }

  public void resourceId(@Nullable String value) {
    resourceId = value;
  }

  public void context(AuditTrail.Context value) {
    context = Objects.requireNonNull(value);
  }

  public void detail(String key, @Nullable Object value) {
    var description = masker.describeCounting(key, value, null, OutputChannel.AUDIT);
    details.put(key, description.value());
    sanitized = Math.addExact(sanitized, description.sanitized());
  }

  public void outcomes(TransactionOutcome local, TransactionOutcome operation) {
    this.local = Objects.requireNonNull(local);
    this.operation = Objects.requireNonNull(operation);
  }

  public AuditEvent event(AuditEvent.Outcome outcome, @Nullable String errorCode) {
    return AuditEvent.builder()
        .id(ids.newId())
        .occurredAt(clock.instant())
        .service(service)
        .action(action)
        .resourceType(resourceType)
        .resourceId(resourceId)
        .actor(context.actor())
        .outcome(outcome)
        .errorCode(errorCode)
        .correlationId(context.correlationId())
        .channel(context.channel())
        .tenant(context.tenant())
        .details(details)
        .transactionOutcome(local)
        .operationOutcome(operation)
        .persistence(null)
        .integrity(null)
        .detailsSanitized(sanitized)
        .build();
  }
}
