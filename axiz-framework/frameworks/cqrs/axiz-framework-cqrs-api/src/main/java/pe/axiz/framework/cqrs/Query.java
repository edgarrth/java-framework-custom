package pe.axiz.framework.cqrs;

/**
 * Request for data without side effects; implemented by records.
 *
 * @param <R> result type
 */
public non-sealed interface Query<R> extends Request<R> {}
