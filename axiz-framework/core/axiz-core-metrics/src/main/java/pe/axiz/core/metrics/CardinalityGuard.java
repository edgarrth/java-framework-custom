package pe.axiz.core.metrics;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps tag cardinality bounded: values beyond the limits, not allowed or malformed are replaced
 * with {@code other}, and a warning is logged once per tag.
 */
final class CardinalityGuard {

  private static final Logger LOG = LoggerFactory.getLogger(CardinalityGuard.class);
  private static final Pattern EVENT_NAME = Pattern.compile("^[a-z0-9]+([._-][a-z0-9]+)*$");
  private static final Pattern TAG_VALUE = Pattern.compile("[^A-Za-z0-9._:-]");
  private static final int MAX_VALUE_LENGTH = 64;

  private final MetricsSettings settings;
  private final Set<String> events = ConcurrentHashMap.newKeySet();
  private final Map<String, Set<String>> values = new ConcurrentHashMap<>();
  private final Set<String> warned = ConcurrentHashMap.newKeySet();

  CardinalityGuard(MetricsSettings settings) {
    this.settings = settings;
  }

  String event(String name) {
    if (name == null || name.length() > MAX_VALUE_LENGTH || !EVENT_NAME.matcher(name).matches()) {
      warnOnce("event-name", "Invalid metric event name; recorded as 'other'");
      return MetricTags.OTHER;
    }
    if (events.contains(name)) {
      return name;
    }
    synchronized (events) {
      if (events.contains(name) || events.size() < settings.maxEvents()) {
        events.add(name);
        return name;
      }
    }
    warnOnce(
        "event-limit",
        "Distinct metric events exceed " + settings.maxEvents() + "; recorded as 'other'");
    return MetricTags.OTHER;
  }

  String channel(@Nullable String value) {
    if (value == null || value.isBlank()) {
      return MetricTags.NONE;
    }
    String v = value.toLowerCase(Locale.ROOT);
    if (settings.channels().contains(v)) {
      return v;
    }
    warnOnce("channel:" + v, "Channel not declared in axiz.metrics.channels; recorded as 'other'");
    return MetricTags.OTHER;
  }

  String standard(String key, @Nullable String value) {
    TagPolicy policy = settings.standardTagPolicies().get(key);
    return bounded(key, value, policy);
  }

  /** Returns null when the custom tag is not declared (it is dropped). */
  @Nullable String custom(String key, @Nullable String value) {
    TagPolicy policy = settings.customTags().get(key);
    if (policy == null) {
      warnOnce(
          "undeclared:" + key,
          "Custom metric tag '" + key + "' is not declared in axiz.metrics.tags; dropped");
      return null;
    }
    return bounded(key, value, policy);
  }

  private String bounded(String key, @Nullable String value, @Nullable TagPolicy policy) {
    if (value == null || value.isBlank()) {
      return MetricTags.NONE;
    }
    String v = clean(value);
    if (policy != null && !policy.allowedValues().isEmpty()) {
      if (policy.allowedValues().contains(v)) {
        return v;
      }
      warnOnce(
          "allowed:" + key, "Value not allowed for metric tag '" + key + "'; recorded as 'other'");
      return MetricTags.OTHER;
    }
    int max = policy != null ? policy.maxDistinctValues() : settings.maxValuesPerTag();
    Set<String> seen = values.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
    if (seen.contains(v)) {
      return v;
    }
    synchronized (seen) {
      if (seen.contains(v) || seen.size() < max) {
        seen.add(v);
        return v;
      }
    }
    warnOnce(
        "limit:" + key,
        "Metric tag '" + key + "' exceeds " + max + " distinct values; recorded as 'other'");
    return MetricTags.OTHER;
  }

  static String clean(String value) {
    String v = TAG_VALUE.matcher(value.strip()).replaceAll("_");
    return v.length() > MAX_VALUE_LENGTH ? v.substring(0, MAX_VALUE_LENGTH) : v;
  }

  private void warnOnce(String key, String message) {
    if (warned.add(key)) {
      LOG.warn(message);
    }
  }
}
