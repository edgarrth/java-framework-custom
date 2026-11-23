package pe.axiz.extension.cqrs;

/**
 * Paso transversal alrededor de los handlers. Los behaviors se ejecutan por {@link #order()}
 * ascendente: los estándar son validación (100), autorización (200), idempotencia (300),
 * transacción (400), auditoría (500) y métricas (600).
 */
public interface PipelineBehavior {

  int VALIDATION = 100;
  int AUTHORIZATION = 200;
  int IDEMPOTENCY = 300;
  int TRANSACTION = 400;
  int AUDIT = 500;
  int METRICS = 600;

  int order();

  <R> R handle(Invocation<R> invocation, Next<R> next);

  /**
   * Continúa el pipeline.
   *
   * @param <R> tipo del resultado
   */
  @FunctionalInterface
  interface Next<R> {
    R proceed();
  }
}
