package pe.axiz.core.featureflags;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Feature flag. Está encendido cuando {@code enabled} y se cumplen todas las restricciones
 * declaradas: canal, tenant, reglas de atributos y el despliegue por porcentaje (estable por clave
 * de segmentación).
 *
 * @param name nombre del flag
 * @param enabled interruptor maestro
 * @param percentage porcentaje de despliegue 0-100
 * @param channels canales permitidos (vacío: cualquiera)
 * @param tenants tenants permitidos (vacío: cualquiera)
 * @param rules atributo a valores permitidos (todos deben coincidir)
 * @param variant valor devuelto por {@link FeatureFlags#variant} cuando está encendido
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
