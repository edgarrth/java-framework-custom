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

  public Composition {
    values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    degraded = List.copyOf(degraded);
  }

  public <T> @Nullable T get(String part, Class<T> type) {
    return type.cast(values.get(part));
  }

  public boolean partial() {
    return !degraded.isEmpty();
  }
}
