package pe.axiz.extension.cqrs;

/**
 * Command protected by an idempotency key: a repeated key returns the first result.
 *
 * @param <R> result type
 */
public interface IdempotentCommand<R> extends Command<R> {

  String idempotencyKey();
}
