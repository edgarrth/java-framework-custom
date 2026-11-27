package pe.axiz.codegen.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Snapshots defensivos de árboles de propiedades YAML y del contenido de archivos generados,
 * preservando el orden.
 */
public final class Snapshots {
  private Snapshots() {}

  /**
   * Copia los contenedores anidados, incluidos los valores YAML null y los escalares mutables
   * binarios o de fecha.
   */
  public static Map<String, Object> properties(Map<String, Object> source) {
    Map<String, Object> copy = new LinkedHashMap<>();
    IdentityHashMap<Object, Object> copies = new IdentityHashMap<>();
    source.forEach((key, value) -> copy.put(key, value(value, 0, copies)));
    return Collections.unmodifiableMap(copy);
  }

  @SuppressWarnings(
      "JavaUtilDate") // las fechas de SnakeYAML son mutables; la copia preserva el valor YAML.
  private static @Nullable Object value(
      @Nullable Object source, int depth, IdentityHashMap<Object, Object> copies) {
    if (depth > 64) {
      throw new IllegalArgumentException("Property tree is cyclic or exceeds 64 levels");
    }
    // Los alias YAML pueden compartir subárboles grandes: cada objeto se copia una vez, no una vez
    // por ruta de alias.
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
    } else if (source instanceof Date date) {
      snapshot = new Date(date.getTime());
    } else {
      return source;
    }
    copies.put(source, snapshot);
    return snapshot;
  }

  /**
   * Cada arreglo de bytes se copia; los invocadores pueden editar su copia sin alterar la salida
   * almacenada.
   */
  public static Map<String, byte[]> files(Map<String, byte[]> source) {
    Map<String, byte[]> copy = new LinkedHashMap<>();
    source.forEach((path, bytes) -> copy.put(path, bytes.clone()));
    return Collections.unmodifiableMap(copy);
  }
}
