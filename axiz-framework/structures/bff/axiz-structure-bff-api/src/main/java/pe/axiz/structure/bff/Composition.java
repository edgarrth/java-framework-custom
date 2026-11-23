package pe.axiz.structure.bff;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Resultado de una ejecución de {@link Composer}.
 *
 * @param values valor de cada parte (el valor alternativo para las partes opcionales degradadas)
 * @param degraded partes opcionales que fallaron o agotaron el tiempo
 */
public record Composition(Map<String, @Nullable Object> values, List<String> degraded) {

  // Constructor canónico explícito, no compacto: javac no escribe el @Nullable de un argumento de
  // tipo en los parámetros de un constructor compacto, así que los llamadores de otros módulos
  // verían Map<String, Object> y NullAway rechazaría un mapa con valores null.
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
