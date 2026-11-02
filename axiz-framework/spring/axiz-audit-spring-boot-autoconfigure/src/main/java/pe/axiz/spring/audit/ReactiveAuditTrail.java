package pe.axiz.spring.audit;

import java.time.Clock;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditRecord;
import pe.axiz.core.audit.AuditTrail;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.sensitive.ObjectMasker;
import reactor.core.publisher.Mono;

/** Sanitized audit snapshots delivered inside the subscriber's reactive context and transaction. */
public final class ReactiveAuditTrail {
  private final String service;
  private final ReactiveAuditPublisher publisher;
  private final ObjectMasker masker;
  private final IdGenerator ids;
  private final Clock clock;

  public ReactiveAuditTrail(
      String service,
      ReactiveAuditPublisher publisher,
      ObjectMasker masker,
      IdGenerator ids,
      Clock clock) {
    this.service = Objects.requireNonNull(service);
    this.publisher = Objects.requireNonNull(publisher);
    this.masker = Objects.requireNonNull(masker);
    this.ids = Objects.requireNonNull(ids);
    this.clock = Objects.requireNonNull(clock);
  }

  public Builder record(String action, String resourceType) {
    return new Builder(action, resourceType);
  }

  /** Builder for a single subscriber; publish operations are deferred until subscription. */
  public final class Builder {
    private final AuditRecord record;

    private Builder(String action, String resourceType) {
      record = new AuditRecord(service, masker, ids, clock, action, resourceType);
    }

    public Builder resourceId(@Nullable String id) {
      record.resourceId(id);
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

    public Mono<Void> success() {
      return publish(AuditEvent.Outcome.SUCCESS, null);
    }

    public Mono<Void> failure(@Nullable String code) {
      return publish(AuditEvent.Outcome.FAILURE, code);
    }

    /** Awaits success delivery, preserves business errors and records cancellation once. */
    public <T> Mono<T> around(Mono<T> work) {
      return ReactiveAudit.around(
          work,
          ignored -> success(),
          error ->
              failure(
                  error instanceof pe.axiz.core.errors.AxizException ax
                      ? ax.code().value()
                      : error.getClass().getSimpleName()));
    }

    private Mono<Void> publish(AuditEvent.Outcome outcome, @Nullable String code) {
      return Mono.defer(() -> publisher.publish(record.event(outcome, code)));
    }
  }
}
