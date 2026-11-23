package pe.axiz.extension.cqrs;

/**
 * Petición de datos sin efectos secundarios; la implementan records.
 *
 * @param <R> tipo del resultado
 */
public non-sealed interface Query<R> extends Request<R> {}
