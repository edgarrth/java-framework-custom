package pe.axiz.extension.cqrs;

/** Envía consultas a través del pipeline hasta su handler. */
public interface QueryBus {

  <R> R ask(Query<R> query);
}
