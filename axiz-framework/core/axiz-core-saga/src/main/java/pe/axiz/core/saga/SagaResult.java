package pe.axiz.core.saga;

import org.jspecify.annotations.Nullable;

/**
 * Resultado de ejecutar una saga.
 *
 * @param sagaId id
 * @param status estado final (o actual)
 * @param context contexto final
 * @param failedStep paso que falló, si existe
 * @param <C> tipo del contexto
 */
public record SagaResult<C>(
    String sagaId, SagaStatus status, C context, @Nullable String failedStep) {

  @Override
  public String toString() {
    return "SagaResult[status=" + status + ", context=****]";
  }

  public boolean completed() {
    return status == SagaStatus.COMPLETED;
  }
}
