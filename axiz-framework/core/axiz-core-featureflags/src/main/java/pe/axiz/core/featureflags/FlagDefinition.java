package pe.axiz.core.featureflags;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A feature flag. It is on when {@code enabled} and every declared restriction matches: channel,
 * tenant, attribute rules and the percentage rollout (stable per targeting key).
 *
 * @param name flag name
 * @param enabled master switch
 * @param percentage rollout percentage 0-100
 * @param channels allowed channels (empty: any)
 * @param tenants allowed tenants (empty: any)
 * @param rules attribute to allowed values (all must match)
 * @param variant value returned by {@link FeatureFlags#variant} when on
 */
public record FlagDefinition(
    String name,
    boolean enabled,
    int percentage,
    Set<String> channels,
    Set<String> tenants,
    Map<String, Set<String>> rules,
    @Nullable String variant) {

  public FlagDefinition {
    Objects.requireNonNull(name);
    if (percentage < 0 || percentage > 100) {
      throw new IllegalArgumentException("percentage must be between 0 and 100");
    }
    channels = Set.copyOf(channels);
    tenants = Set.copyOf(tenants);
    rules = Map.copyOf(rules);
  }

  public static FlagDefinition on(String name) {
    return new FlagDefinition(name, true, 100, Set.of(), Set.of(), Map.of(), null);
  }

  public static FlagDefinition off(String name) {
    return new FlagDefinition(name, false, 0, Set.of(), Set.of(), Map.of(), null);
  }
}
