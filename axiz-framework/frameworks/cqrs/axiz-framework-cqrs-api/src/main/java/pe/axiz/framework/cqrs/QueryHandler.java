package pe.axiz.framework.cqrs;

/**
 * Handles one query type; never changes state.
 *
 * @param <Q> query type
 * @param <R> result type
 */
public interface QueryHandler<Q extends Query<R>, R> {

  R handle(Q query);
}
