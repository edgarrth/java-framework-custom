package pe.axiz.core.featureflags;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Para quién se evalúa un flag.
 *
 * @param targetingKey clave estable para despliegues por porcentaje (id de usuario o de cliente)
 * @param channel canal
 * @param tenant tenant
 * @param attributes otros atributos para las reglas
 */
public record EvaluationContext(
    @Nullable String targetingKey,
    @Nullable String channel,
    @Nullable String tenant,
    Map<String, String> attributes) {

  public EvaluationContext {
    attributes = Map.copyOf(attributes);
  }

  public static EvaluationContext empty() {
    return new EvaluationContext(null, null, null, Map.of());
  }

  public static EvaluationContext of(String targetingKey) {
    return new EvaluationContext(targetingKey, null, null, Map.of());
  }
}
