package pe.axiz.spring.featureflags;

import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.core.featureflags.FlagDefinition;

/**
 * Feature flags ({@code axiz.feature-flags.flags.<name>}) from the service ConfigMap or Parameter
 * Store (both reach the environment as properties).
 *
 * @param flags flags by name
 */
@ConfigurationProperties("axiz.feature-flags")
public record AxizFeatureFlagsProperties(@DefaultValue Map<String, Flag> flags) {
  public AxizFeatureFlagsProperties {
    flags = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(flags));
  }

  /**
   * One flag.
   *
   * @param enabled master switch
   * @param percentage rollout percentage (stable per user or client)
   * @param channels allowed channels; empty allows any
   * @param tenants allowed tenants; empty allows any
   * @param rules attribute name to allowed values
   * @param variant value returned when the flag is on
   */
  public record Flag(
      @DefaultValue("false") boolean enabled,
      @DefaultValue("100") int percentage,
      @DefaultValue Set<String> channels,
      @DefaultValue Set<String> tenants,
      @DefaultValue Map<String, Set<String>> rules,
      @Nullable String variant) {
    public Flag {
      channels = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(channels));
      java.util.Map<String, Set<String>> copiedRules = new java.util.LinkedHashMap<>();
      rules.forEach((key, values) -> copiedRules.put(key, Set.copyOf(values)));
      rules = java.util.Collections.unmodifiableMap(copiedRules);
      tenants = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(tenants));
    }

    FlagDefinition toDefinition(String name) {
      return new FlagDefinition(name, enabled, percentage, channels, tenants, rules, variant);
    }
  }
}
