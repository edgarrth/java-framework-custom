package pe.axiz.spring.audit;

import org.jspecify.annotations.Nullable;
import pe.axiz.core.audit.AuditTrail;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import reactor.util.context.ContextView;

/** Resolves custom context against the subscriber metadata and restores the executing thread. */
final class ReactiveAuditContext {
  private ReactiveAuditContext() {}

  static AuditTrail.Context current(
      AuditContextResolver resolver, ContextView context, @Nullable String ip) {
    RequestMetadata before = RequestContext.get();
    RequestMetadata subscriber = context.getOrDefault(RequestContext.KEY, null);
    RequestContext.restore(subscriber);
    try {
      return resolver.current(ip);
    } finally {
      RequestContext.restore(before);
    }
  }
}
