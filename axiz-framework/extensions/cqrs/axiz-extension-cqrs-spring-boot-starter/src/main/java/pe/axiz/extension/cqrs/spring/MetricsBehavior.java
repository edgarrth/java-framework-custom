package pe.axiz.extension.cqrs.spring;

import java.util.List;
import java.util.Objects;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.extension.cqrs.Invocation;
import pe.axiz.extension.cqrs.PipelineBehavior;

/**
 * Cuenta y mide cada comando y consulta como un evento genérico con el nombre de la petición
 * ({@code create-card}); las peticiones y los resultados que implementan {@code MetricSource}
 * agregan montos y tags.
 */
public final class MetricsBehavior implements PipelineBehavior {

  private final EventMetrics metrics;

  public MetricsBehavior(EventMetrics metrics) {
    this.metrics = Objects.requireNonNull(metrics);
  }

  @Override
  public int order() {
    return METRICS;
  }

  @Override
  public <R> R handle(Invocation<R> invocation, Next<R> next) {
    try {
      return metrics.observe(
          invocation.name(),
          invocation.isCommand() ? "command" : "query",
          true,
          List.of(invocation.request()),
          next::proceed);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new IllegalStateException(t);
    }
  }
}
