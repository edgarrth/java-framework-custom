package pe.axiz.core.metrics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Settings of the generic event metrics (bound from {@code axiz.metrics.*}).
 *
 * @param maxEvents maximum distinct event names; the excess is recorded as {@code other}
 * @param maxValuesPerTag default maximum distinct values per tag
 * @param channels allowed channel values
 * @param standardTagPolicies policies for standard tags (category, product, reason, stage, tenant)
 * @param customTags declared custom tags; any other custom tag is dropped
 * @param events optional event catalog
 * @param percentileHistograms whether to publish histogram buckets for distributions
 */
public record MetricsSettings(
    int maxEvents,
    int maxValuesPerTag,
    Set<String> channels,
    Map<String, TagPolicy> standardTagPolicies,
    Map<String, TagPolicy> customTags,
    Map<String, EventDefinition> events,
    boolean percentileHistograms) {

  public static final List<String> DEFAULT_CHANNELS =
      List.of("web", "mobile", "branch", "atm", "pos", "partner", "batch", "internal");

  public MetricsSettings {
    if (maxEvents <= 0 || maxValuesPerTag <= 0) {
      throw new IllegalArgumentException("limits must be positive");
    }
    channels = Set.copyOf(channels);
    standardTagPolicies = Map.copyOf(standardTagPolicies);
    customTags = Map.copyOf(customTags);
    events = Map.copyOf(events);
  }

  public static MetricsSettings defaults() {
    return new MetricsSettings(
        200, 50, Set.copyOf(DEFAULT_CHANNELS), Map.of(), Map.of(), Map.of(), true);
  }

  public MetricsSettings withCustomTags(TagPolicy... policies) {
    var map = new LinkedHashMap<>(customTags);
    for (TagPolicy p : policies) {
      map.put(p.key(), p);
    }
    return new MetricsSettings(
        maxEvents,
        maxValuesPerTag,
        channels,
        standardTagPolicies,
        map,
        events,
        percentileHistograms);
  }

  public MetricsSettings withEvents(EventDefinition... definitions) {
    var map = new LinkedHashMap<>(events);
    for (EventDefinition d : definitions) {
      map.put(d.name(), d);
    }
    return new MetricsSettings(
        maxEvents,
        maxValuesPerTag,
        channels,
        standardTagPolicies,
        customTags,
        map,
        percentileHistograms);
  }
}
