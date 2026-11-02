package pe.axiz.extension.cqrs.spring;

import pe.axiz.extension.cqrs.Command;
import reactor.core.publisher.Mono;

/** Sends commands through the reactive pipeline to their {@code ReactiveCommandHandler}. */
public interface ReactiveCommandBus {

  /** Nothing runs until the returned {@code Mono} is subscribed. */
  <R> Mono<R> send(Command<R> command);
}
