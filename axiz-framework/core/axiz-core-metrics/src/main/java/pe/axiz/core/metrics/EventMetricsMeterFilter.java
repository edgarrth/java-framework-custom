package pe.axiz.core.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Meter filter for the {@code axiz.events*} instruments: denies meters with undeclared tag keys
 * (defense in depth against ad-hoc tags) and applies the histogram buckets of the event catalog.
 * Common tags (service, env, version, domain) are allowed.
 */
public final class EventMetricsMeterFilter implements MeterFilter {

  private static final Set<String> STANDARD =
      Set.of(
          MetricTags.EVENT,
          MetricTags.CATEGORY,
          MetricTags.OUTCOME,
          MetricTags.REASON,
          MetricTags.CHANNEL,
          MetricTags.PRODUCT,
          MetricTags.CURRENCY,
          MetricTags.STAGE,
          MetricTags.TENANT,
          MetricTags.UNIT);

  private final MetricsSettings settings;
  private final Set<String> allowed;

  public EventMetricsMeterFilter(MetricsSettings settings, Set<String> commonTagKeys) {
    this.settings = settings;
    Set<String> keys = new HashSet<>(STANDARD);
    keys.addAll(settings.customTags().keySet());
    keys.addAll(commonTagKeys);
    this.allowed = Set.copyOf(keys);
  }

  @Override
  public MeterFilterReply accept(Meter.Id id) {
    if (!MetricNames.isEventMetric(id.getName())) {
      return MeterFilterReply.NEUTRAL;
    }
    for (Tag tag : id.getTagsAsIterable()) {
      if (!allowed.contains(tag.getKey())) {
        return MeterFilterReply.DENY;
      }
    }
    return MeterFilterReply.NEUTRAL;
  }

  @Override
  public @Nullable DistributionStatisticConfig configure(
      Meter.Id id, DistributionStatisticConfig config) {
    if (!MetricNames.isEventMetric(id.getName())) {
      return config;
    }
    String event = id.getTag(MetricTags.EVENT);
    EventDefinition def = event == null ? null : settings.events().get(event);
    if (def == null) {
      return config;
    }
    double[] slos =
        switch (id.getName()) {
          case MetricNames.DURATION ->
              def.durationSlos().stream().mapToDouble(d -> (double) d.toNanos()).toArray();
          case MetricNames.AMOUNT -> toArray(def.amountSlos());
          case MetricNames.VALUE -> toArray(def.valueSlos());
          default -> new double[0];
        };
    if (slos.length == 0) {
      return config;
    }
    return DistributionStatisticConfig.builder().serviceLevelObjectives(slos).build().merge(config);
  }

  private static double[] toArray(List<Double> values) {
    return values.stream().mapToDouble(Double::doubleValue).toArray();
  }

  /** Duration helper for catalogs declared in milliseconds. */
  public static Duration millis(long ms) {
    return Duration.ofMillis(ms);
  }
}
