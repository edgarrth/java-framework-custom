package pe.axiz.core.saga;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Persisted state of a saga instance.
 *
 * @param sagaId instance id
 * @param sagaName definition name
 * @param status status
 * @param stepIndex next step to execute (running) or last step to compensate (compensating)
 * @param context encoded context
 * @param error last error
 * @param updatedAt last update
 * @param version optimistic lock version
 */
public record SagaState(
    String sagaId,
    String sagaName,
    SagaStatus status,
    int stepIndex,
    String context,
    @Nullable String error,
    Instant updatedAt,
    long version) {

  public SagaState {
    Objects.requireNonNull(sagaId);
    Objects.requireNonNull(sagaName);
    Objects.requireNonNull(status);
    Objects.requireNonNull(context);
    Objects.requireNonNull(updatedAt);
  }

  @Override
  public String toString() {
    return "SagaState[status=" + status + ", version=" + version + ", context=****]";
  }

  SagaState next(
      SagaStatus newStatus, int newStep, String newContext, @Nullable String newError, Instant at) {
    return SagaState.builder()
        .sagaId(sagaId)
        .sagaName(sagaName)
        .status(newStatus)
        .stepIndex(newStep)
        .context(newContext)
        .error(newError)
        .updatedAt(at)
        .version(version + 1)
        .build();
  }

  /** Named construction; required values and invariants are checked by build. */
  public static Builder builder() {
    return new Builder();
  }

  /** Mutable construction state; use one builder per operation, never as shared state. */
  public static final class Builder {
    private @Nullable String sagaId = null;
    private @Nullable String sagaName = null;
    private @Nullable SagaStatus status = null;
    private int stepIndex = 0;
    private @Nullable String context = null;
    private @Nullable String error = null;
    private @Nullable Instant updatedAt = null;
    private long version = 0;

    private Builder() {}

    public Builder sagaId(String value) {
      this.sagaId = value;
      return this;
    }

    public Builder sagaName(String value) {
      this.sagaName = value;
      return this;
    }

    public Builder status(SagaStatus value) {
      this.status = value;
      return this;
    }

    public Builder stepIndex(int value) {
      this.stepIndex = value;
      return this;
    }

    public Builder context(String value) {
      this.context = value;
      return this;
    }

    public Builder error(@Nullable String value) {
      this.error = value;
      return this;
    }

    public Builder updatedAt(Instant value) {
      this.updatedAt = value;
      return this;
    }

    public Builder version(long value) {
      this.version = value;
      return this;
    }

    public SagaState build() {
      return new SagaState(
          Objects.requireNonNull(sagaId, "sagaId"),
          Objects.requireNonNull(sagaName, "sagaName"),
          Objects.requireNonNull(status, "status"),
          stepIndex,
          Objects.requireNonNull(context, "context"),
          error,
          Objects.requireNonNull(updatedAt, "updatedAt"),
          version);
    }
  }
}
