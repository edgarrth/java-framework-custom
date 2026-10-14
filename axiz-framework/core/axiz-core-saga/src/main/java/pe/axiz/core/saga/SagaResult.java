package pe.axiz.core.saga;

import org.jspecify.annotations.Nullable;

/**
 * Outcome of running a saga.
 *
 * @param sagaId id
 * @param status final (or current) status
 * @param context final context
 * @param failedStep step that failed, if any
 * @param <C> context type
 */
public record SagaResult<C>(
    String sagaId, SagaStatus status, C context, @Nullable String failedStep) {

  public boolean completed() {
    return status == SagaStatus.COMPLETED;
  }
}
