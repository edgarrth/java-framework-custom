package pe.axiz.core.audit;

import java.time.Clock;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.TransactionOutcome;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.sensitive.ObjectMasker;

/**
 * Builds sanitized audit events and hands them to the publisher. Sanitizing is always active: the
 * details go through the {@link ObjectMasker} for the audit channel.
 */
public final class AuditTrail {

  private final String service;
  private final AuditPublisher publisher;
  private final ObjectMasker masker;
  private final IdGenerator ids;
  private final Clock clock;

  public AuditTrail(
      String service, AuditPublisher publisher, ObjectMasker masker, IdGenerator ids, Clock clock) {
    this.service = Objects.requireNonNull(service);
    this.publisher = Objects.requireNonNull(publisher);
    this.masker = Objects.requireNonNull(masker);
    this.ids = Objects.requireNonNull(ids);
    this.clock = Objects.requireNonNull(clock);
  }

  /** Starts an audit record for an action on a resource. */
  public Builder record(String action, String resourceType) {
    return new Builder(action, resourceType);
  }

  /**
   * Context of the current request used to fill actor, correlation, channel and tenant.
   *
   * @param actor actor
   * @param correlationId correlation id
   * @param channel channel
   * @param tenant tenant
   */
  public record Context(
      AuditEvent.Actor actor,
      @Nullable String correlationId,
      @Nullable String channel,
      @Nullable String tenant) {
    public static Context system() {
      return new Context(AuditEvent.Actor.system(), null, null, null);
    }
  }

  /** Builder of one audit record. */
  public final class Builder {
    private final AuditRecord record;

    private Builder(String action, String resourceType) {
      record = new AuditRecord(service, masker, ids, clock, action, resourceType);
    }

    public Builder resourceId(@Nullable String value) {
      record.resourceId(value);
      return this;
    }

    public Builder context(Context value) {
      record.context(value);
      return this;
    }

    /** Adds a detail; the value is sanitized for the audit channel. */
    public Builder detail(String key, @Nullable Object value) {
      record.detail(key, value);
      return this;
    }

    public Builder outcomes(TransactionOutcome local, TransactionOutcome operation) {
      record.outcomes(local, operation);
      return this;
    }

    public void success() {
      publish(AuditEvent.Outcome.SUCCESS, null);
    }

    public void failure(@Nullable String errorCode) {
      publish(AuditEvent.Outcome.FAILURE, errorCode);
    }

    /** Runs the action and records success or failure according to the policy. */
    public <T> T around(
        AuditPolicy policy,
        Supplier<T> action,
        Function<RuntimeException, @Nullable String> errorCode) {
      try {
        T result = action.get();
        if (policy.applies(AuditEvent.Outcome.SUCCESS)) {
          success();
        }
        return result;
      } catch (RuntimeException e) {
        if (policy.applies(AuditEvent.Outcome.FAILURE)) {
          failure(errorCode.apply(e));
        }
        throw e;
      }
    }

    private void publish(AuditEvent.Outcome outcome, @Nullable String errorCode) {
      publisher.publish(record.event(outcome, errorCode));
    }
  }
}
