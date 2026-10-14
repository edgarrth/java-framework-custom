package pe.axiz.framework.cqrs.spring;

import pe.axiz.framework.cqrs.Query;
import reactor.core.publisher.Mono;

/** Asks queries through the reactive pipeline to their {@code ReactiveQueryHandler}. */
public interface ReactiveQueryBus {

  /** Nothing runs until the returned {@code Mono} is subscribed. */
  <R> Mono<R> ask(Query<R> query);
}
