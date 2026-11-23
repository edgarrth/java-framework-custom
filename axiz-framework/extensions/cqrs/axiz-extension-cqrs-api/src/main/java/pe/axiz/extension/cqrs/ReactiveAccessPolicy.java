package pe.axiz.extension.cqrs;

import org.reactivestreams.Publisher;

/**
 * Contrato de autorización no bloqueante, independiente de Spring y de Reactor. El publisher
 * termina vacío para permitir el acceso o falla para denegarlo, antes de la idempotencia, las
 * transacciones y el handler.
 *
 * @param <R> tipo de petición (o un supertipo compartido)
 */
public interface ReactiveAccessPolicy<R> {

  Class<R> requestType();

  Publisher<Void> check(R request, Caller caller);
}
