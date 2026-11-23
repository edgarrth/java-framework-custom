package pe.axiz.extension.cqrs;

/**
 * Intención de cambiar el estado; la implementan records.
 *
 * @param <R> tipo del resultado ({@code Void} cuando no hay)
 */
public non-sealed interface Command<R> extends Request<R> {}
