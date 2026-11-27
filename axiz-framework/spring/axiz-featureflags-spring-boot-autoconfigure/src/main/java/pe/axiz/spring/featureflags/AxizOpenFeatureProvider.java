package pe.axiz.spring.featureflags;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import pe.axiz.core.featureflags.FeatureFlags;

/**
 * Proveedor de OpenFeature respaldado por los flags configurados. Se admiten flags booleanos y de
 * texto (variante); los demás tipos devuelven el valor por defecto.
 */
public class AxizOpenFeatureProvider implements FeatureProvider {

  private final FeatureFlags flags;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Rejects invalid construction; no finalizer or publication of this. The overridable "
              + "Spring SPI remains extensible; this constructor acquires no external resource.")
  public AxizOpenFeatureProvider(FeatureFlags flags) {
    this.flags = Objects.requireNonNull(flags);
  }

  @Override
  public Metadata getMetadata() {
    return () -> "axiz";
  }

  @Override
  public ProviderEvaluation<Boolean> getBooleanEvaluation(
      String key, Boolean defaultValue, EvaluationContext ctx) {
    if (!flags.isDefined(key)) {
      return notFound(key, defaultValue);
    }
    boolean on = flags.isEnabled(key, convert(ctx));
    return ProviderEvaluation.<Boolean>builder()
        .value(on)
        .reason(Reason.TARGETING_MATCH.name())
        .build();
  }

  @Override
  public ProviderEvaluation<String> getStringEvaluation(
      String key, String defaultValue, EvaluationContext ctx) {
    if (!flags.isDefined(key)) {
      return notFound(key, defaultValue);
    }
    String v = flags.variant(key, convert(ctx), defaultValue);
    return ProviderEvaluation.<String>builder()
        .value(v)
        .variant(v)
        .reason(Reason.TARGETING_MATCH.name())
        .build();
  }

  /**
   * Un flag desconocido produce el valor por defecto del invocante y lo indica (OpenFeature {@code
   * FLAG_NOT_FOUND}), en lugar de responder "off" en silencio: un error tipográfico en el nombre de
   * un flag debe ser visible, no un cambio de comportamiento.
   */
  private static <T> ProviderEvaluation<T> notFound(String key, T defaultValue) {
    return ProviderEvaluation.<T>builder()
        .value(defaultValue)
        .reason(Reason.ERROR.name())
        .errorCode(ErrorCode.FLAG_NOT_FOUND)
        .errorMessage("flag '" + key + "' is not configured")
        .build();
  }

  @Override
  public ProviderEvaluation<Integer> getIntegerEvaluation(
      String key, Integer defaultValue, EvaluationContext ctx) {
    return ProviderEvaluation.<Integer>builder()
        .value(defaultValue)
        .reason(Reason.DEFAULT.name())
        .build();
  }

  @Override
  public ProviderEvaluation<Double> getDoubleEvaluation(
      String key, Double defaultValue, EvaluationContext ctx) {
    return ProviderEvaluation.<Double>builder()
        .value(defaultValue)
        .reason(Reason.DEFAULT.name())
        .build();
  }

  @Override
  public ProviderEvaluation<Value> getObjectEvaluation(
      String key, Value defaultValue, EvaluationContext ctx) {
    return ProviderEvaluation.<Value>builder()
        .value(defaultValue)
        .reason(Reason.DEFAULT.name())
        .build();
  }

  static pe.axiz.core.featureflags.EvaluationContext convert(EvaluationContext ctx) {
    pe.axiz.core.featureflags.EvaluationContext request = FlagContexts.current();
    if (ctx == null) {
      return request;
    }
    Map<String, String> attributes = new HashMap<>();
    ctx.asObjectMap()
        .forEach(
            (k, v) -> {
              if (v != null) {
                attributes.put(k, v.toString());
              }
            });
    String targeting =
        ctx.getTargetingKey() != null ? ctx.getTargetingKey() : request.targetingKey();
    String channel =
        attributes.containsKey("channel") ? attributes.get("channel") : request.channel();
    String tenant = attributes.containsKey("tenant") ? attributes.get("tenant") : request.tenant();
    return new pe.axiz.core.featureflags.EvaluationContext(targeting, channel, tenant, attributes);
  }
}
