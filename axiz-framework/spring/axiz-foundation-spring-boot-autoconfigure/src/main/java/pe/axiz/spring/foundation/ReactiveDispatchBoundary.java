package pe.axiz.spring.foundation;

import reactor.core.publisher.Mono;

/** Decorates the complete message execution, outside its reactive transaction. */
@FunctionalInterface
public interface ReactiveDispatchBoundary {
  Mono<Void> wrap(Mono<Void> execution);
}
