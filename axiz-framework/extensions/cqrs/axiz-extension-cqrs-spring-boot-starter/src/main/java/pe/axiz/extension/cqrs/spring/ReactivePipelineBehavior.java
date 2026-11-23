package pe.axiz.extension.cqrs.spring;

import pe.axiz.extension.cqrs.Invocation;
import pe.axiz.extension.cqrs.PipelineBehavior;
import reactor.core.publisher.Mono;

/**
 * Paso transversal alrededor de los handlers reactivos, con las mismas constantes de orden que
 * {@link PipelineBehavior}. Un behavior nunca bloquea: los colaboradores bloqueantes (almacén de
 * idempotencia, publicador de auditoría) corren en el scheduler bounded elastic.
 */
public interface ReactivePipelineBehavior {

  int order();

  <R> Mono<R> handle(Invocation<R> invocation, Next<R> next);

  /**
   * Continúa el pipeline.
   *
   * @param <R> tipo del resultado
   */
  @FunctionalInterface
  interface Next<R> {
    Mono<R> proceed();
  }
}
