package pe.axiz.extension.cqrs;

/**
 * Mensaje manejado por exactamente un handler.
 *
 * @param <R> tipo del resultado
 */
public sealed interface Request<R> permits Command, Query {}
