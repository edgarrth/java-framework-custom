package pe.axiz.framework.bff;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Result of a {@link Composer} run.
 *
 * @param values value of every part (fallback for degraded optional parts)
 * @param degraded optional parts that failed or timed out
 */
public record Composition(Map<String, @Nullable Object> values, List<String> degraded) {

  // An explicit canonical constructor, not a compact one: javac does not write the @Nullable of a
  // type argument on the parameters of a compact constructor, so callers in other modules would
  // see Map<String, Object> and NullAway would reject a map with null values.
  public Composition(Map<String, @Nullable Object> values, List<String> degraded) {
    this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    this.degraded = List.copyOf(degraded);
  }

  public <T> @Nullable T get(String part, Class<T> type) {
    return type.cast(values.get(part));
  }

  public boolean partial() {
    return !degraded.isEmpty();
  }
}
