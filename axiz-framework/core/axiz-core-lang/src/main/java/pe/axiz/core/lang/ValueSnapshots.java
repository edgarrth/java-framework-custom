package pe.axiz.core.lang;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Detached immutable scalar/map/list value trees, preserving null values and bounded nesting. */
public final class ValueSnapshots {
  private ValueSnapshots() {}

  public static Map<String, @Nullable Object> map(Map<String, ?> source) {
    return map(source, 0);
  }

  private static Map<String, @Nullable Object> map(Map<?, ?> source, int depth) {
    Map<String, @Nullable Object> copy = new LinkedHashMap<>();
    source.forEach(
        (key, value) -> {
          if (!(key instanceof String name))
            throw new IllegalArgumentException("Value keys must be strings");
          copy.put(name, value(value, depth + 1));
        });
    return Collections.unmodifiableMap(copy);
  }

  private static @Nullable Object value(@Nullable Object source, int depth) {
    if (depth > 64) throw new IllegalArgumentException("Value snapshot nesting exceeds 64 levels");
    if (source instanceof Map<?, ?> map) return map(map, depth);
    if (source instanceof List<?> list) {
      List<@Nullable Object> copy = new ArrayList<>();
      for (@Nullable Object item : list) copy.add(value(item, depth + 1));
      return Collections.unmodifiableList(copy);
    }
    if ((source instanceof Float floatValue && !Float.isFinite(floatValue))
        || (source instanceof Double doubleValue && !Double.isFinite(doubleValue)))
      throw new IllegalArgumentException("Value numbers must be finite");
    if (source == null
        || source instanceof String
        || source instanceof Boolean
        || source.getClass() == BigDecimal.class
        || source.getClass() == BigInteger.class
        || source instanceof Byte
        || source instanceof Short
        || source instanceof Integer
        || source instanceof Long
        || source instanceof Float
        || source instanceof Double) return source;
    throw new IllegalArgumentException(
        "Value snapshots accept only maps, lists and immutable scalar values");
  }
}
