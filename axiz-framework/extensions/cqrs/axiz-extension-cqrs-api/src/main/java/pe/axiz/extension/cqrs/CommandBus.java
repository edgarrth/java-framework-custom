package pe.axiz.extension.cqrs;

/** Sends commands through the pipeline to their handler. */
public interface CommandBus {

  <R> R send(Command<R> command);
}
