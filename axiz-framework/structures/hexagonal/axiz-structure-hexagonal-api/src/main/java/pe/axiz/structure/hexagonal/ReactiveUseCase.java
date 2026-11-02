package pe.axiz.structure.hexagonal;

import org.reactivestreams.Publisher;

/**
 * An asynchronous use case. Work starts on subscription, emits at most one result and propagates
 * cancellation.
 */
@FunctionalInterface
public interface ReactiveUseCase<I, O> {
  Publisher<O> execute(I input);
}
