package pe.axiz.extension.cqrs.spring;

import pe.axiz.extension.cqrs.Query;
import reactor.core.publisher.Mono;

/** Asks queries through the reactive pipeline to their {@code ReactiveQueryHandler}. */
public interface ReactiveQueryBus {

  /** Nothing runs until the returned {@code Mono} is subscribed. */
  <R> Mono<R> ask(Query<R> query);
}
