package pe.axiz.test.support;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import pe.axiz.core.metrics.MetricNames;
import pe.axiz.core.metrics.MetricTags;

/** Lee métricas genéricas de eventos en pruebas sin depender de un framework de pruebas. */
public final class MetricAssertions {

  private MetricAssertions() {}

  /** Cantidad de un evento con un resultado (sumando todas las demás etiquetas). */
  public static double count(MeterRegistry registry, String event, String outcome) {
    return registry
        .find(MetricNames.COUNT)
        .tag(MetricTags.EVENT, event)
        .tag(MetricTags.OUTCOME, outcome)
        .counters()
        .stream()
        .mapToDouble(Counter::count)
        .sum();
  }

  /** Monto total de un evento en una moneda. */
  public static double amount(MeterRegistry registry, String event, String currency) {
    return registry
        .find(MetricNames.AMOUNT)
        .tag(MetricTags.EVENT, event)
        .tag(MetricTags.CURRENCY, currency)
        .summaries()
        .stream()
        .mapToDouble(DistributionSummary::totalAmount)
        .sum();
  }

  /** Falla con un mensaje legible cuando la cantidad difiere. */
  public static void assertCount(
      MeterRegistry registry, String event, String outcome, double expected) {
    double actual = count(registry, event, outcome);
    if (Double.compare(actual, expected) != 0) {
      throw new AssertionError(
          "Expected "
              + expected
              + " '"
              + event
              + "' events with outcome '"
              + outcome
              + "' but was "
              + actual);
    }
  }
}
