package pe.axiz.payments.ux.composition;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.featureflags.EvaluationContext;
import reactor.core.publisher.Mono;

/**
 * El cliente autenticado, tomado del contexto de Reactor que completan los filtros de seguridad: el
 * id del cliente es el subject del token. Las API de canales nunca aceptan un id de cliente desde
 * la solicitud.
 *
 * @param id id del cliente (subject del token)
 * @param channel cabecera del canal, si se envía
 * @param tenant tenant, si existe
 */
record Customer(String id, @Nullable String channel, @Nullable String tenant) {

  static Mono<Customer> current() {
    return Mono.deferContextual(
        ctx -> {
          RequestMetadata m = ctx.getOrDefault(RequestContext.KEY, null);
          String subject = m == null ? null : m.subject();
          if (m == null || subject == null || subject.isBlank()) {
            return Mono.error(
                new AxizException(CoreErrors.UNAUTHENTICATED, "no customer in the token"));
          }
          return Mono.just(new Customer(subject, m.channel(), m.tenantId()));
        });
  }

  /**
   * Contexto para los feature flags: segmentación por cliente y restricciones por canal y tenant.
   */
  EvaluationContext flags() {
    return new EvaluationContext(id, channel, tenant, Map.of());
  }
}
