package pe.axiz.core.metrics;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Entrada opcional del catálogo para un evento: fija su categoría, la unidad de su valor y los
 * buckets del histograma (SLO) de sus distribuciones. Los eventos funcionan sin una definición.
 *
 * @param name nombre del evento
 * @param category categoría
 * @param valueUnit unidad de {@code axiz.events.value} para este evento (registros, ítems, puntos,
 *     etc.)
 * @param durationSlos buckets de duración
 * @param amountSlos buckets de monto
 * @param valueSlos buckets de valor
 */
public record EventDefinition(
    String name,
    @Nullable String category,
    @Nullable String valueUnit,
    List<Duration> durationSlos,
    List<Double> amountSlos,
    List<Double> valueSlos) {

  public EventDefinition {
    Objects.requireNonNull(name);
    durationSlos = List.copyOf(durationSlos);
    amountSlos = List.copyOf(amountSlos);
    valueSlos = List.copyOf(valueSlos);
  }

  public static EventDefinition of(String name, String category) {
    return new EventDefinition(name, category, null, List.of(), List.of(), List.of());
  }
}
