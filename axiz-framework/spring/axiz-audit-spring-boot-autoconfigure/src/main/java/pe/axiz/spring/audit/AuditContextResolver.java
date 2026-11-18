package pe.axiz.spring.audit;

import org.jspecify.annotations.Nullable;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditTrail;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;

/** Builds the audit context (actor, correlation, channel, tenant) from the current request. */
@FunctionalInterface
public interface AuditContextResolver {

  AuditTrail.Context current(@Nullable String ip);

  /** Uses {@link RequestContext}, which the web and security starters fill. */
  static AuditContextResolver fromRequestContext() {
    return ip -> {
      RequestMetadata m = RequestContext.get();
      if (m == null) {
        return ip == null
            ? AuditTrail.Context.system()
            : new AuditTrail.Context(new AuditEvent.Actor(null, null, ip), null, null, null);
      }
      return new AuditTrail.Context(
          new AuditEvent.Actor(m.subject(), m.clientId(), ip),
          m.correlationId(),
          m.channel(),
          m.tenantId());
    };
  }
}
