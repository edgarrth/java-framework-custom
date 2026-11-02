package pe.axiz.extension.cqrs;

/**
 * Keeps a read model up to date from events (domain events in process, or integration events
 * through the EDA style).
 *
 * @param <E> event type
 */
public interface Projection<E> {

  void project(E event);
}
