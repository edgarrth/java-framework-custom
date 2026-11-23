package pe.axiz.core.metrics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configuración de las métricas genéricas de eventos (enlazada desde {@code axiz.metrics.*}).
 *
 * @param maxEvents máximo de nombres de evento distintos; el excedente se registra como {@code
 *     other}
 * @param maxValuesPerTag máximo por defecto de valores distintos por tag
 * @param channels valores de canal permitidos
 * @param standardTagPolicies políticas de los tags estándar (category, product, reason, stage,
 *     tenant)
 * @param customTags tags personalizados declarados; cualquier otro tag personalizado se descarta
 * @param events catálogo de eventos opcional
 * @param percentileHistograms indica si se publican los buckets del histograma para las
 *     distribuciones
 */
public record MetricsSettings(
    int maxEvents,
    int maxValuesPerTag,
    Set<String> channels,
    Map<String, TagPolicy> standardTagPolicies,
    Map<String, TagPolicy> customTags,
    Map<String, EventDefinition> events,
    boolean percentileHistograms) {

  public static final List<String> DEFAULT_CHANNELS =
      List.of("web", "mobile", "branch", "atm", "pos", "partner", "batch", "internal");

  public MetricsSettings {
    if (maxEvents <= 0 || maxValuesPerTag <= 0) {
      throw new IllegalArgumentException("limits must be positive");
    }
    channels = Set.copyOf(channels);
    standardTagPolicies = Map.copyOf(standardTagPolicies);
    customTags = Map.copyOf(customTags);
    events = Map.copyOf(events);
  }

  public static MetricsSettings defaults() {
    return new MetricsSettings(
        200, 50, Set.copyOf(DEFAULT_CHANNELS), Map.of(), Map.of(), Map.of(), true);
  }

  public MetricsSettings withCustomTags(TagPolicy... policies) {
    var map = new LinkedHashMap<>(customTags);
    for (TagPolicy p : policies) {
      map.put(p.key(), p);
    }
    return new MetricsSettings(
        maxEvents,
        maxValuesPerTag,
        channels,
        standardTagPolicies,
        map,
        events,
        percentileHistograms);
  }

  public MetricsSettings withEvents(EventDefinition... definitions) {
    var map = new LinkedHashMap<>(events);
    for (EventDefinition d : definitions) {
      map.put(d.name(), d);
    }
    return new MetricsSettings(
        maxEvents,
        maxValuesPerTag,
        channels,
        standardTagPolicies,
        customTags,
        map,
        percentileHistograms);
  }
}
