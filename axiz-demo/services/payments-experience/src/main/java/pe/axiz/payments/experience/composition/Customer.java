package pe.axiz.payments.experience.composition;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.featureflags.EvaluationContext;
import reactor.core.publisher.Mono;

/**
 * The signed-in customer, taken from the Reactor context the security filters fill: the customer id
 * is the token subject. Channel APIs never accept a customer id from the request.
 *
 * @param id customer id (token subject)
 * @param channel channel header, if sent
 * @param tenant tenant, if any
 */
record Customer(String id, @Nullable String channel, @Nullable String tenant) {

  static Mono<Customer> current() {
    return Mono.deferContextual(
        ctx -> {
          RequestMetadata m = ctx.getOrDefault(RequestContext.KEY, null);
          if (m == null || m.subject() == null || m.subject().isBlank()) {
            return Mono.error(
                new AxizException(CoreErrors.UNAUTHENTICATED, "no customer in the token"));
          }
          return Mono.just(new Customer(m.subject(), m.channel(), m.tenantId()));
        });
  }

  /** Context for feature flags: targeting by customer, restrictions by channel and tenant. */
  EvaluationContext flags() {
    return new EvaluationContext(id, channel, tenant, Map.of());
  }
}
