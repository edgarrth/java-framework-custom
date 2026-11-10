package pe.axiz.spring.metrics;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.core.metrics.MetricsSettings;

/**
 * Generic event metrics ({@code axiz.metrics.*}), usually declared in the service ConfigMap.
 *
 * @param maxEvents maximum distinct event names (the excess is recorded as {@code other})
 * @param maxValuesPerTag default maximum distinct values per tag
 * @param channels allowed channel values
 * @param standardTags limits for standard tags (category, product, reason, stage, tenant)
 * @param tags declared custom tags; undeclared tags are rejected
 * @param events optional event catalog: category, value unit and histogram buckets
 * @param percentileHistograms whether distributions publish histogram buckets
 * @param domain business domain added as a common tag
 * @param version service version added as a common tag; defaults to the build version
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

  /** Default channel list, kept in sync with the core settings. */
  public static final List<String> DEFAULT_CHANNELS = MetricsSettings.DEFAULT_CHANNELS;

  /**
   * Tag policy: an allow-list of values or a maximum of distinct values.
   *
   * @param allowed allowed values (empty means any value up to {@code max})
   * @param max maximum distinct values
   */
  public record Tag(@DefaultValue Set<String> allowed, @DefaultValue("50") int max) {
    public Tag {
      allowed = Collections.unmodifiableSet(new LinkedHashSet<>(allowed));
    }
  }

  /**
   * Event definition.
   *
   * @param category category
   * @param valueUnit unit of the value instrument
   * @param durationSlos duration buckets
   * @param amountSlos amount buckets
   * @param valueSlos value buckets
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
}
