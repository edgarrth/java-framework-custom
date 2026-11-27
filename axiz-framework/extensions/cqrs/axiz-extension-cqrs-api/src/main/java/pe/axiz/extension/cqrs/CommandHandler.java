package pe.axiz.extension.cqrs;

/**
 * Maneja un tipo de comando.
 *
 * @param <C> tipo del comando
 * @param <R> tipo del resultado
 */
public interface CommandHandler<C extends Command<R>, R> {

  R handle(C command);
}
