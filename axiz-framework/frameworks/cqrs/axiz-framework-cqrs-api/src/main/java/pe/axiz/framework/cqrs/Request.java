package pe.axiz.framework.cqrs;

/**
 * A message handled by exactly one handler.
 *
 * @param <R> result type
 */
public sealed interface Request<R> permits Command, Query {}
