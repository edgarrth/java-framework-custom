package pe.axiz.core.audit;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.sensitive.ObjectMasker;
import pe.axiz.core.sensitive.OutputChannel;

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
    private final String action;
    private final String resourceType;
    private @Nullable String resourceId;
    private Context context = Context.system();
    private final Map<String, @Nullable Object> details = new LinkedHashMap<>();

    private Builder(String action, String resourceType) {
      this.action = action;
      this.resourceType = resourceType;
    }

    public Builder resourceId(@Nullable String value) {
      this.resourceId = value;
      return this;
    }

    public Builder context(Context value) {
      this.context = value;
      return this;
    }

    /** Adds a detail; the value is sanitized for the audit channel. */
    public Builder detail(String key, @Nullable Object value) {
      details.put(key, masker.describe(value, OutputChannel.AUDIT));
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
        java.util.function.Function<RuntimeException, @Nullable String> errorCode) {
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
      publisher.publish(
          new AuditEvent(
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
              details));
    }
  }
}
