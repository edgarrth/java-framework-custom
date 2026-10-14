package pe.axiz.framework.cqrs;

import org.reactivestreams.Publisher;

/**
 * Handles one command type without blocking (reactive services on WebFlux and R2DBC). The publisher
 * emits at most one value, usually a {@code Mono}; it runs in the reactive transaction and the rest
 * of the pipeline of the reactive bus.
 *
 * @param <C> command type
 * @param <R> result type
 */
public interface ReactiveCommandHandler<C extends Command<R>, R> {

  Publisher<R> handle(C command);
}
