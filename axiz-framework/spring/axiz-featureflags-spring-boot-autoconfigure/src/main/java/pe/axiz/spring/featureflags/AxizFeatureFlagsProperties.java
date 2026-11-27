package pe.axiz.spring.featureflags;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.core.featureflags.FlagDefinition;

/**
 * Feature flags ({@code axiz.feature-flags.flags.<name>}) tomados del ConfigMap del servicio o de
 * Parameter Store (ambos llegan al entorno como propiedades).
 *
 * @param flags flags por nombre
 */
@ConfigurationProperties("axiz.feature-flags")
public record AxizFeatureFlagsProperties(@DefaultValue Map<String, Flag> flags) {
  public AxizFeatureFlagsProperties {
    flags = Collections.unmodifiableMap(new LinkedHashMap<>(flags));
  }

  /**
   * Un flag.
   *
   * @param enabled interruptor general
   * @param percentage porcentaje de despliegue (estable por usuario o cliente)
   * @param channels canales permitidos; vacío permite cualquiera
   * @param tenants tenants permitidos; vacío permite cualquiera
   * @param rules nombre de atributo asociado a los valores permitidos
   * @param variant valor devuelto cuando el flag está activo
   */
  public record Flag(
      @DefaultValue("false") boolean enabled,
      @DefaultValue("100") int percentage,
      @DefaultValue Set<String> channels,
      @DefaultValue Set<String> tenants,
      @DefaultValue Map<String, Set<String>> rules,
      @Nullable String variant) {
    public Flag {
      channels = Collections.unmodifiableSet(new LinkedHashSet<>(channels));
      Map<String, Set<String>> copiedRules = new LinkedHashMap<>();
      rules.forEach((key, values) -> copiedRules.put(key, Set.copyOf(values)));
      rules = Collections.unmodifiableMap(copiedRules);
      tenants = Collections.unmodifiableSet(new LinkedHashSet<>(tenants));
    }

    FlagDefinition toDefinition(String name) {
      return new FlagDefinition(name, enabled, percentage, channels, tenants, rules, variant);
    }
  }
}
