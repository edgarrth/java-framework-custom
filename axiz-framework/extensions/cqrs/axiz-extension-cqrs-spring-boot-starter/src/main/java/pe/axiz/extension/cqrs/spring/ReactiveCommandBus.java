package pe.axiz.extension.cqrs.spring;

import pe.axiz.extension.cqrs.Command;
import reactor.core.publisher.Mono;

/** Envía comandos a través del pipeline reactivo hasta su {@code ReactiveCommandHandler}. */
public interface ReactiveCommandBus {

  /** No se ejecuta nada hasta que se suscribe el {@code Mono} devuelto. */
  <R> Mono<R> send(Command<R> command);
}
