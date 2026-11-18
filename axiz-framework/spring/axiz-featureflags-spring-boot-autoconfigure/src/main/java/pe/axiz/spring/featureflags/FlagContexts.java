package pe.axiz.spring.featureflags;

import java.util.Map;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.featureflags.EvaluationContext;

/**
 * Evaluation context of the current request: user (or client) as targeting key, channel, tenant.
 */
public final class FlagContexts {

  private FlagContexts() {}

  public static EvaluationContext current() {
    RequestMetadata m = RequestContext.get();
    if (m == null) {
      return EvaluationContext.empty();
    }
    String key = m.subject() != null ? m.subject() : m.clientId();
    return new EvaluationContext(key, m.channel(), m.tenantId(), Map.of());
  }
}
