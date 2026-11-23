package pe.axiz.extension.cqrs;

import org.reactivestreams.Publisher;

/**
 * Maneja un tipo de comando sin bloquear (servicios reactivos sobre WebFlux y R2DBC). El publisher
 * emite como máximo un valor, normalmente un {@code Mono}; corre en la transacción reactiva y en el
 * resto del pipeline del bus reactivo.
 *
 * @param <C> tipo del comando
 * @param <R> tipo del resultado
 */
public interface ReactiveCommandHandler<C extends Command<R>, R> {

  Publisher<R> handle(C command);
}
