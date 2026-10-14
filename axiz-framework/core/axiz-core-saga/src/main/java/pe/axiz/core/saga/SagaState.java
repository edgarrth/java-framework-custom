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

  SagaState next(
      SagaStatus newStatus, int newStep, String newContext, @Nullable String newError, Instant at) {
    return new SagaState(
        sagaId, sagaName, newStatus, newStep, newContext, newError, at, version + 1);
  }
}
