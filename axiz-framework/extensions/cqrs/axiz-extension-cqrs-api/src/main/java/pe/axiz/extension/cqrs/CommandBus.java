package pe.axiz.extension.cqrs;

/** Envía comandos a través del pipeline hasta su handler. */
public interface CommandBus {

  <R> R send(Command<R> command);
}
