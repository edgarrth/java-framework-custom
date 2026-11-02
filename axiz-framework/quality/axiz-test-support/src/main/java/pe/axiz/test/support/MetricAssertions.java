package pe.axiz.test.support;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import pe.axiz.core.metrics.MetricNames;
import pe.axiz.core.metrics.MetricTags;

/** Reads generic event metrics in tests without depending on a test framework. */
public final class MetricAssertions {

  private MetricAssertions() {}

  /** Count of an event with an outcome (all other tags summed). */
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

  /** Total amount of an event in a currency. */
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

  /** Fails with a readable message when the count differs. */
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
