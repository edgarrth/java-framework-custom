package pe.axiz.structure.hexagonal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.domain.AggregateRoot;
import pe.axiz.core.domain.DomainEvent;

/**
 * Recolecta los eventos de dominio lanzados durante el caso de uso actual. Los casos de uso llaman
 * a {@link #collect(AggregateRoot)} después de guardar un agregado; el runner los publica en el
 * commit.
 */
public final class DomainEvents {

  private static final ThreadLocal<@Nullable List<DomainEvent>> CURRENT = new ThreadLocal<>();

  private DomainEvents() {}

  /** Mueve los eventos pendientes de un agregado al caso de uso actual. */
  public static void collect(AggregateRoot<?> aggregate) {
    raise(aggregate.pullEvents());
  }

  public static void raise(Collection<? extends DomainEvent> events) {
    List<DomainEvent> current = CURRENT.get();
    if (current == null) {
      throw new IllegalStateException("Domain events can only be raised inside a use case");
    }
    current.addAll(events);
  }

  static @Nullable List<DomainEvent> begin() {
    List<DomainEvent> previous = CURRENT.get();
    CURRENT.set(new ArrayList<>());
    return previous;
  }

  static List<DomainEvent> drain() {
    List<DomainEvent> current = CURRENT.get();
    if (current == null) {
      return List.of();
    }
    List<DomainEvent> out = List.copyOf(current);
    current.clear();
    return out;
  }

  static void end(@Nullable List<DomainEvent> previous) {
    if (previous == null) {
      CURRENT.remove();
    } else {
      CURRENT.set(previous);
    }
  }
}
