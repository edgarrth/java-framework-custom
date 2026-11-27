package pe.axiz.spring.foundation;

import reactor.core.publisher.Mono;

/** Decora la ejecución completa del mensaje, fuera de su transacción reactiva. */
@FunctionalInterface
public interface ReactiveDispatchBoundary {
  Mono<Void> wrap(Mono<Void> execution);
}
