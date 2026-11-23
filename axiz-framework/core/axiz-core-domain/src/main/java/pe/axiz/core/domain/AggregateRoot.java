package pe.axiz.core.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Raíz de agregado: frontera de consistencia que registra eventos de dominio mientras cambia. La
 * capa de aplicación extrae los eventos tras guardar y los publica una vez que la transacción hace
 * commit.
 *
 * @param <I> tipo del id
 */
public abstract class AggregateRoot<I> extends Entity<I> {

  private final List<DomainEvent> pendingEvents = new ArrayList<>();

  protected void registerEvent(DomainEvent event) {
    pendingEvents.add(event);
  }

  /** Eventos registrados desde la última extracción, sin eliminarlos. */
  public List<DomainEvent> peekEvents() {
    return List.copyOf(pendingEvents);
  }

  /** Devuelve y limpia los eventos pendientes. */
  public List<DomainEvent> pullEvents() {
    List<DomainEvent> events = List.copyOf(pendingEvents);
    pendingEvents.clear();
    return events;
  }
}
