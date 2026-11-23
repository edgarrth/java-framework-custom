package pe.axiz.core.featureflags;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Evalúa feature flags. Los flags desconocidos están apagados. Los despliegues por porcentaje
 * calculan el hash de {@code flag + targetingKey}, de modo que el mismo usuario siempre obtiene la
 * misma respuesta y los distintos flags son independientes.
 */
public final class FeatureFlags {

  private final FlagProvider provider;

  public FeatureFlags(FlagProvider provider) {
    this.provider = Objects.requireNonNull(provider);
  }

  /**
   * Indica si el flag está configurado (un flag desconocido está apagado, pero no es lo mismo que
   * apagado).
   */
  public boolean isDefined(String flag) {
    return provider.find(flag).isPresent();
  }

  public boolean isEnabled(String flag, EvaluationContext ctx) {
    Optional<FlagDefinition> def = provider.find(flag);
    return def.isPresent() && evaluate(def.get(), ctx);
  }

  public String variant(String flag, EvaluationContext ctx, String defaultValue) {
    Optional<FlagDefinition> def = provider.find(flag);
    if (def.isEmpty() || !evaluate(def.get(), ctx)) {
      return defaultValue;
    }
    String v = def.get().variant();
    return v == null ? defaultValue : v;
  }

  static boolean evaluate(FlagDefinition d, EvaluationContext ctx) {
    if (!d.enabled()) {
      return false;
    }
    if (!d.channels().isEmpty()
        && (ctx.channel() == null || !d.channels().contains(ctx.channel()))) {
      return false;
    }
    if (!d.tenants().isEmpty() && (ctx.tenant() == null || !d.tenants().contains(ctx.tenant()))) {
      return false;
    }
    for (Map.Entry<String, Set<String>> rule : d.rules().entrySet()) {
      String value = ctx.attributes().get(rule.getKey());
      if (value == null || !rule.getValue().contains(value)) {
        return false;
      }
    }
    if (d.percentage() >= 100) {
      return true;
    }
    if (d.percentage() <= 0 || ctx.targetingKey() == null) {
      return false;
    }
    return bucket(d.name(), ctx.targetingKey()) < d.percentage();
  }

  /** Bucket estable 0-99. */
  static int bucket(String flag, String key) {
    try {
      byte[] h =
          MessageDigest.getInstance("SHA-256")
              .digest((flag + ":" + key).getBytes(StandardCharsets.UTF_8));
      int v = ((h[0] & 0xFF) << 24) | ((h[1] & 0xFF) << 16) | ((h[2] & 0xFF) << 8) | (h[3] & 0xFF);
      return Math.floorMod(v, 100);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
