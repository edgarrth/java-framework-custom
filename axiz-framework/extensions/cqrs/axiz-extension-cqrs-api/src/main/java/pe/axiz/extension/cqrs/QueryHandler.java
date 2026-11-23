package pe.axiz.extension.cqrs;

/**
 * Maneja un tipo de consulta; nunca cambia el estado.
 *
 * @param <Q> tipo de la consulta
 * @param <R> tipo del resultado
 */
public interface QueryHandler<Q extends Query<R>, R> {

  R handle(Q query);
}
