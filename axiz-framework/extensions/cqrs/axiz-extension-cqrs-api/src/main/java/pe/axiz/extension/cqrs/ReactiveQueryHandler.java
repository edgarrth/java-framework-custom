package pe.axiz.extension.cqrs;

import org.reactivestreams.Publisher;

/**
 * Maneja un tipo de consulta sin bloquear; nunca cambia el estado. El publisher emite como máximo
 * un valor (una página o una lista son un valor).
 *
 * @param <Q> tipo de la consulta
 * @param <R> tipo del resultado
 */
public interface ReactiveQueryHandler<Q extends Query<R>, R> {

  Publisher<R> handle(Q query);
}
