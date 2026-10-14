package pe.axiz.framework.cqrs;

/**
 * Handles one command type.
 *
 * @param <C> command type
 * @param <R> result type
 */
public interface CommandHandler<C extends Command<R>, R> {

  R handle(C command);
}
