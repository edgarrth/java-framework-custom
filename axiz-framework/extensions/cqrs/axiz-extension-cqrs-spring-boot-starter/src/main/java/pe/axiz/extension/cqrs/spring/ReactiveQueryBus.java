package pe.axiz.extension.cqrs.spring;

import pe.axiz.extension.cqrs.Query;
import reactor.core.publisher.Mono;

/** Envía consultas a través del pipeline reactivo hasta su {@code ReactiveQueryHandler}. */
public interface ReactiveQueryBus {

  /** No se ejecuta nada hasta que se suscribe el {@code Mono} devuelto. */
  <R> Mono<R> ask(Query<R> query);
}
