package pe.axiz.framework.cqrs;

/** Sends commands through the pipeline to their handler. */
public interface CommandBus {

  <R> R send(Command<R> command);
}
