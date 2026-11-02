package pe.axiz.extension.cqrs.spring;

import java.util.Map;
import java.util.Objects;
import pe.axiz.core.observability.ArchitectureObservation;
import pe.axiz.core.observability.AxizObservations;
import pe.axiz.extension.cqrs.Invocation;
import pe.axiz.extension.cqrs.PipelineBehavior;

/** Outermost step: a span {@code axiz.command} or {@code axiz.query} around the whole pipeline. */
public class ObservationBehavior implements PipelineBehavior {

  private final ArchitectureObservation observation;

  public ObservationBehavior(ArchitectureObservation observation) {
    this.observation = Objects.requireNonNull(observation);
  }

  @Override
  public int order() {
    return 0;
  }

  @Override
  public <R> R handle(Invocation<R> invocation, Next<R> next) {
    return observation.observe(
        invocation.isCommand() ? AxizObservations.COMMAND : AxizObservations.QUERY,
        invocation.name(),
        Map.of(),
        Map.of(),
        next::proceed);
  }
}
