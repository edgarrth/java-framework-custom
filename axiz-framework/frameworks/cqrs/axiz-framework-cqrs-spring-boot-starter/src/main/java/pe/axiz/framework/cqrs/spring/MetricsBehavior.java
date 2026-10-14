package pe.axiz.framework.cqrs.spring;

import java.util.List;
import java.util.Objects;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.framework.cqrs.Invocation;
import pe.axiz.framework.cqrs.PipelineBehavior;

/**
 * Counts and times every command and query as a generic event named after the request ({@code
 * create-card}); requests and results implementing {@code MetricSource} add amounts and tags.
 */
public class MetricsBehavior implements PipelineBehavior {

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
