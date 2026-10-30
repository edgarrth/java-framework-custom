package pe.axiz.codegen.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Defensive snapshots of YAML property trees and generated file contents, preserving order. */
public final class Snapshots {
  private Snapshots() {}

  /** Copies nested containers, including null YAML values and mutable binary/date scalars. */
  public static Map<String, Object> properties(Map<String, Object> source) {
    Map<String, Object> copy = new LinkedHashMap<>();
    Map<Object, Object> copies = new IdentityHashMap<>();
    source.forEach((key, value) -> copy.put(key, value(value, 0, copies)));
    return Collections.unmodifiableMap(copy);
  }

  private static @Nullable Object value(
      @Nullable Object source, int depth, Map<Object, Object> copies) {
    if (depth > 64) {
      throw new IllegalArgumentException("Property tree is cyclic or exceeds 64 levels");
    }
    // YAML aliases may share large subtrees: copy each object once, not once per alias path.
    Object previous = copies.get(source);
    if (previous != null) {
      return previous;
    }
    Object snapshot;
    if (source instanceof Map<?, ?> map) {
      Map<Object, Object> copy = new LinkedHashMap<>();
      map.forEach(
          (key, item) -> copy.put(value(key, depth + 1, copies), value(item, depth + 1, copies)));
      snapshot = Collections.unmodifiableMap(copy);
    } else if (source instanceof List<?> list) {
      List<Object> copy = new ArrayList<>();
      list.forEach(item -> copy.add(value(item, depth + 1, copies)));
      snapshot = Collections.unmodifiableList(copy);
    } else if (source instanceof Set<?> set) {
      Set<Object> copy = new LinkedHashSet<>();
      set.forEach(item -> copy.add(value(item, depth + 1, copies)));
      snapshot = Collections.unmodifiableSet(copy);
    } else if (source instanceof byte[] bytes) {
      snapshot = bytes.clone();
    } else if (source instanceof java.util.Date date) {
      snapshot = new java.util.Date(date.getTime());
    } else {
      return source;
    }
    copies.put(source, snapshot);
    return snapshot;
  }

  /** Each byte array is copied; callers may edit their copy without altering stored output. */
  public static Map<String, byte[]> files(Map<String, byte[]> source) {
    Map<String, byte[]> copy = new LinkedHashMap<>();
    source.forEach((path, bytes) -> copy.put(path, bytes.clone()));
    return Collections.unmodifiableMap(copy);
  }
}
