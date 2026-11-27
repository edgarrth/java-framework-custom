package pe.axiz.extension.cqrs;

/**
 * Comando protegido por una clave de idempotencia: una clave repetida devuelve el primer resultado.
 *
 * @param <R> tipo del resultado
 */
public interface IdempotentCommand<R> extends Command<R> {

  String idempotencyKey();
}
