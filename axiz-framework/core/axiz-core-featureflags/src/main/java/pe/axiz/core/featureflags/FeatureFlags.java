package pe.axiz.core.featureflags;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Evaluates feature flags. Unknown flags are off. Percentage rollouts hash {@code flag +
 * targetingKey} so the same user always gets the same answer and different flags are independent.
 */
public final class FeatureFlags {

  private final FlagProvider provider;

  public FeatureFlags(FlagProvider provider) {
    this.provider = Objects.requireNonNull(provider);
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

  /** Stable bucket 0-99. */
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
