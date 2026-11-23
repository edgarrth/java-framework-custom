package pe.axiz.core.saga;

import java.util.List;
import java.util.Objects;

/**
 * Pasos ordenados de una saga.
 *
 * @param name nombre de la saga, por ejemplo {@code payment}
 * @param steps pasos en orden de ejecución
 * @param <C> tipo del contexto
 */
public record SagaDefinition<C>(String name, List<SagaStep<C>> steps) {

  public SagaDefinition {
    Objects.requireNonNull(name);
    steps = List.copyOf(steps);
    if (steps.isEmpty()) {
      throw new IllegalArgumentException("A saga needs at least one step");
    }
  }
}
