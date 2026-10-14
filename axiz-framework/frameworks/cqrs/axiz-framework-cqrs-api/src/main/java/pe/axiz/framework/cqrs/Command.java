package pe.axiz.framework.cqrs;

/**
 * Intent to change state; implemented by records.
 *
 * @param <R> result type ({@code Void} when there is none)
 */
public non-sealed interface Command<R> extends Request<R> {}
