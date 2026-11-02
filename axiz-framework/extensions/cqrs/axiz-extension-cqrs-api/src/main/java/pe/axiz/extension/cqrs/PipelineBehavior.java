package pe.axiz.extension.cqrs;

/**
 * Cross-cutting step around handlers. Behaviors run by ascending {@link #order()}: the standard
 * ones are validation (100), authorization (200), idempotency (300), transaction (400), audit (500)
 * and metrics (600).
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
   * Continues the pipeline.
   *
   * @param <R> result type
   */
  @FunctionalInterface
  interface Next<R> {
    R proceed();
  }
}
