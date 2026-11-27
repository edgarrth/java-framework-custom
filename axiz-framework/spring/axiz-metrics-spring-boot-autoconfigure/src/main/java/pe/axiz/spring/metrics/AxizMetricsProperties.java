package pe.axiz.spring.metrics;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.core.metrics.MetricsSettings;

/**
 * Métricas genéricas de eventos ({@code axiz.metrics.*}), normalmente declaradas en el ConfigMap
 * del servicio.
 *
 * @param maxEvents máximo de nombres de evento distintos (el exceso se registra como {@code other})
 * @param maxValuesPerTag máximo por defecto de valores distintos por etiqueta
 * @param channels valores de canal permitidos
 * @param standardTags límites para las etiquetas estándar (category, product, reason, stage,
 *     tenant)
 * @param tags etiquetas personalizadas declaradas; las no declaradas se rechazan
 * @param events catálogo opcional de eventos: categoría, unidad del valor y buckets del histograma
 * @param percentileHistograms indica si las distribuciones publican buckets de histograma
 * @param domain dominio de negocio añadido como etiqueta común
 * @param version versión del servicio añadida como etiqueta común; por defecto, la versión del
 *     build
 */
@ConfigurationProperties("axiz.metrics")
public record AxizMetricsProperties(
    @DefaultValue("200") int maxEvents,
    @DefaultValue("50") int maxValuesPerTag,
    @DefaultValue({"web", "mobile", "branch", "atm", "pos", "partner", "batch", "internal"})
        Set<String> channels,
    @DefaultValue Map<String, Tag> standardTags,
    @DefaultValue Map<String, Tag> tags,
    @DefaultValue Map<String, Event> events,
    @DefaultValue("true") boolean percentileHistograms,
    @Nullable String domain,
    @Nullable String version) {
  public AxizMetricsProperties {
    channels = Collections.unmodifiableSet(new LinkedHashSet<>(channels));
    events = Collections.unmodifiableMap(new LinkedHashMap<>(events));
    standardTags = Collections.unmodifiableMap(new LinkedHashMap<>(standardTags));
    tags = Collections.unmodifiableMap(new LinkedHashMap<>(tags));
  }

  /** Lista de canales por defecto, sincronizada con la configuración del core. */
  public static final List<String> DEFAULT_CHANNELS = MetricsSettings.DEFAULT_CHANNELS;

  /**
   * Política de etiquetas: una lista de valores permitidos o un máximo de valores distintos.
   *
   * @param allowed valores permitidos (vacío significa cualquier valor hasta {@code max})
   * @param max máximo de valores distintos
   */
  public record Tag(@DefaultValue Set<String> allowed, @DefaultValue("50") int max) {
    public Tag {
      allowed = Collections.unmodifiableSet(new LinkedHashSet<>(allowed));
    }
  }

  /**
   * Definición de un evento.
   *
   * @param category categoría
   * @param valueUnit unidad del instrumento de valor
   * @param durationSlos buckets de duración
   * @param amountSlos buckets de monto
   * @param valueSlos buckets de valor
   */
  public record Event(
      @Nullable String category,
      @Nullable String valueUnit,
      @DefaultValue List<Duration> durationSlos,
      @DefaultValue List<Double> amountSlos,
      @DefaultValue List<Double> valueSlos) {
    public Event {
      amountSlos = List.copyOf(amountSlos);
      durationSlos = List.copyOf(durationSlos);
      valueSlos = List.copyOf(valueSlos);
    }
  }

  /** Construcción con nombre; build comprueba los valores obligatorios y las invariantes. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Estado mutable de construcción; se usa un builder por operación, nunca como estado compartido.
   */
  public static final class Builder {
    private int maxEvents = 0;
    private int maxValuesPerTag = 0;
    private @Nullable Set<String> channels = Set.of();
    private @Nullable Map<String, Tag> standardTags = Map.of();
    private @Nullable Map<String, Tag> tags = Map.of();
    private @Nullable Map<String, Event> events = Map.of();
    private boolean percentileHistograms = false;
    private @Nullable String domain = null;
    private @Nullable String version = null;

    private Builder() {}

    public Builder maxEvents(int value) {
      this.maxEvents = value;
      return this;
    }

    public Builder maxValuesPerTag(int value) {
      this.maxValuesPerTag = value;
      return this;
    }

    public Builder channels(Set<String> value) {
      this.channels = Set.copyOf(value);
      return this;
    }

    public Builder standardTags(Map<String, Tag> value) {
      this.standardTags = Map.copyOf(value);
      return this;
    }

    public Builder tags(Map<String, Tag> value) {
      this.tags = Map.copyOf(value);
      return this;
    }

    public Builder events(Map<String, Event> value) {
      this.events = Map.copyOf(value);
      return this;
    }

    public Builder percentileHistograms(boolean value) {
      this.percentileHistograms = value;
      return this;
    }

    public Builder domain(@Nullable String value) {
      this.domain = value;
      return this;
    }

    public Builder version(@Nullable String value) {
      this.version = value;
      return this;
    }

    public AxizMetricsProperties build() {
      return new AxizMetricsProperties(
          maxEvents,
          maxValuesPerTag,
          Objects.requireNonNull(channels, "channels"),
          Objects.requireNonNull(standardTags, "standardTags"),
          Objects.requireNonNull(tags, "tags"),
          Objects.requireNonNull(events, "events"),
          percentileHistograms,
          domain,
          version);
    }
  }
}
