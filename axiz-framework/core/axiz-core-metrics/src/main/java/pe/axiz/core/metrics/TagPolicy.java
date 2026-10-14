package pe.axiz.core.metrics;

import java.util.Objects;
import java.util.Set;

/**
 * Policy of a tag: an explicit set of allowed values or, when empty, a maximum of distinct values.
 *
 * @param key tag key
 * @param allowedValues allowed values; empty means any value up to {@code maxDistinctValues}
 * @param maxDistinctValues limit of distinct values when no allowed set is given
 */
public record TagPolicy(String key, Set<String> allowedValues, int maxDistinctValues) {

  public TagPolicy {
    Objects.requireNonNull(key);
    allowedValues = Set.copyOf(allowedValues);
    if (maxDistinctValues <= 0) {
      throw new IllegalArgumentException("maxDistinctValues must be positive");
    }
  }

  public static TagPolicy allowed(String key, Set<String> values) {
    return new TagPolicy(key, values, Math.max(values.size(), 1));
  }

  public static TagPolicy bounded(String key, int max) {
    return new TagPolicy(key, Set.of(), max);
  }
}
