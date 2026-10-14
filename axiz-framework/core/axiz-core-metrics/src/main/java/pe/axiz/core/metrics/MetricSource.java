package pe.axiz.core.metrics;

/**
 * Implemented by results (or commands) that contribute to the metric recorded by {@link Metered}:
 * amount, value and tags. A plain interface instead of expressions, so it works the same in a
 * native image.
 */
@FunctionalInterface
public interface MetricSource {

  void describe(MetricEvent event);
}
