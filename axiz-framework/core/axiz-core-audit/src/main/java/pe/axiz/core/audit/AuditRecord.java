package pe.axiz.core.audit;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
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
    details.put(key, masker.describe(value, OutputChannel.AUDIT));
  }

  public AuditEvent event(AuditEvent.Outcome outcome, @Nullable String errorCode) {
    return new AuditEvent(
        ids.newId(),
        clock.instant(),
        service,
        action,
        resourceType,
        resourceId,
        context.actor(),
        outcome,
        errorCode,
        context.correlationId(),
        context.channel(),
        context.tenant(),
        details);
  }
}
