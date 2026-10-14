package pe.axiz.core.featureflags;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Who a flag is evaluated for.
 *
 * @param targetingKey stable key for percentage rollouts (user or client id)
 * @param channel channel
 * @param tenant tenant
 * @param attributes other attributes for rules
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
