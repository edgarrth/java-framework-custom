package pe.axiz.structure.hexagonal;

import org.reactivestreams.Publisher;

/**
 * Caso de uso asíncrono. El trabajo empieza con la suscripción, emite como máximo un resultado y
 * propaga la cancelación.
 */
@FunctionalInterface
public interface ReactiveUseCase<I, O> {
  Publisher<O> execute(I input);
}
