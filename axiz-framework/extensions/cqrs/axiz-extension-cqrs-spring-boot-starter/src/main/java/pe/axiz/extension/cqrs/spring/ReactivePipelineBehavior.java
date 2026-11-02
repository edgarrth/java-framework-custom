package pe.axiz.extension.cqrs.spring;

import pe.axiz.extension.cqrs.Invocation;
import pe.axiz.extension.cqrs.PipelineBehavior;
import reactor.core.publisher.Mono;

/**
 * Cross-cutting step around reactive handlers, with the same order constants as {@link
 * PipelineBehavior}. A behavior never blocks: blocking collaborators (idempotency store, audit
 * publisher) run on the bounded elastic scheduler.
 */
public interface ReactivePipelineBehavior {

  int order();

  <R> Mono<R> handle(Invocation<R> invocation, Next<R> next);

  /**
   * Continues the pipeline.
   *
   * @param <R> result type
   */
  @FunctionalInterface
  interface Next<R> {
    Mono<R> proceed();
  }
}
