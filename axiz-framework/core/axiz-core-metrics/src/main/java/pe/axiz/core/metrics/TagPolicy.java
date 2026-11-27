package pe.axiz.core.metrics;

import java.util.Objects;
import java.util.Set;

/**
 * Política de un tag: un conjunto explícito de valores permitidos o, cuando está vacío, un máximo
 * de valores distintos.
 *
 * @param key clave del tag
 * @param allowedValues valores permitidos; vacío significa cualquier valor hasta {@code
 *     maxDistinctValues}
 * @param maxDistinctValues límite de valores distintos cuando no se da un conjunto permitido
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
