package pe.axiz.framework.cqrs;

/** Sends queries through the pipeline to their handler. */
public interface QueryBus {

  <R> R ask(Query<R> query);
}
