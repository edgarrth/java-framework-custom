package pe.axiz.framework.cqrs;

import org.reactivestreams.Publisher;

/**
 * Handles one query type without blocking; never changes state. The publisher emits at most one
 * value (a page or a list is one value).
 *
 * @param <Q> query type
 * @param <R> result type
 */
public interface ReactiveQueryHandler<Q extends Query<R>, R> {

  Publisher<R> handle(Q query);
}
