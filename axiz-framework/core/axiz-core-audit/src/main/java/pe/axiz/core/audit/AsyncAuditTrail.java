package pe.axiz.core.audit;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.TransactionOutcome;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.sensitive.ObjectMasker;

/**
 * Registros de auditoría saneados con aceptación asíncrona nativa; sin executor ni puente
 * bloqueante.
 */
public final class AsyncAuditTrail {
  private final String service;
  private final AsyncAuditEventPublisher publisher;
  private final ObjectMasker masker;
  private final IdGenerator ids;
  private final Clock clock;

  public AsyncAuditTrail(
      String service,
      AsyncAuditEventPublisher publisher,
      ObjectMasker masker,
      IdGenerator ids,
      Clock clock) {
    this.service = Objects.requireNonNull(service);
    this.publisher = Objects.requireNonNull(publisher);
    this.masker = Objects.requireNonNull(masker);
    this.ids = Objects.requireNonNull(ids);
    this.clock = Objects.requireNonNull(clock);
  }

  /**
   * Los builders pertenecen a una operación de registro y no deben compartirse entre llamadas
   * concurrentes.
   */
  public Builder record(String action, String resourceType) {
    return new Builder(action, resourceType);
  }

  /** Builder de un registro asíncrono saneado. */
  public final class Builder {
    private final AuditRecord record;

    private Builder(String action, String resourceType) {
      record = new AuditRecord(service, masker, ids, clock, action, resourceType);
    }

    public Builder resourceId(@Nullable String value) {
      record.resourceId(value);
      return this;
    }

    public Builder context(AuditTrail.Context value) {
      record.context(value);
      return this;
    }

    public Builder detail(String key, @Nullable Object value) {
      record.detail(key, value);
      return this;
    }

    public Builder outcomes(TransactionOutcome local, TransactionOutcome operation) {
      record.outcomes(local, operation);
      return this;
    }

    public CompletionStage<Void> success() {
      return publish(AuditEvent.Outcome.SUCCESS, null);
    }

    public CompletionStage<Void> failure(@Nullable String errorCode) {
      return publish(AuditEvent.Outcome.FAILURE, errorCode);
    }

    private CompletionStage<Void> publish(AuditEvent.Outcome outcome, @Nullable String errorCode) {
      return Stages.call(() -> publisher.publish(record.event(outcome, errorCode)));
    }
  }
}
