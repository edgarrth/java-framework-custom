package pe.axiz.extension.cqrs;

/**
 * Mantiene actualizado un modelo de lectura a partir de eventos (eventos de dominio en proceso, o
 * eventos de integración mediante el estilo EDA).
 *
 * @param <E> tipo del evento
 */
public interface Projection<E> {

  void project(E event);
}
