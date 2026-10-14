package pe.axiz.core.saga;

import java.util.List;
import java.util.Objects;

/**
 * Ordered steps of a saga.
 *
 * @param name saga name, e.g. {@code payment}
 * @param steps steps in execution order
 * @param <C> context type
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
