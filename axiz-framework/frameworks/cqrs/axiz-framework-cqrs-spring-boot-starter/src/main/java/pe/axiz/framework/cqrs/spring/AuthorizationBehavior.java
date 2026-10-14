package pe.axiz.framework.cqrs.spring;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.errors.AccessException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.framework.cqrs.AccessPolicies;
import pe.axiz.framework.cqrs.Caller;
import pe.axiz.framework.cqrs.Invocation;
import pe.axiz.framework.cqrs.PipelineBehavior;
import pe.axiz.framework.cqrs.RequiresAuthority;

/**
 * Authorization stage of the pipeline: {@link RequiresAuthority} on the request type (403 when the
 * caller lacks it), then every matching {@code AccessPolicy} (object-level checks). It runs before
 * idempotency, so a stored result is never returned to a caller who may no longer send the request.
 */
public class AuthorizationBehavior implements PipelineBehavior {

  private final AccessPolicies policies;

  public AuthorizationBehavior() {
    this(AccessPolicies.none());
  }

  public AuthorizationBehavior(AccessPolicies policies) {
    this.policies = Objects.requireNonNull(policies);
  }

  @Override
  public int order() {
    return AUTHORIZATION;
  }

  @Override
  public <R> R handle(Invocation<R> invocation, Next<R> next) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    RequiresAuthority rule = rule(invocation);
    if (rule != null) {
      check(rule, auth, invocation.name());
    }
    policies.check(invocation.request(), caller(auth, RequestContext.get()));
    return next.proceed();
  }

  static @Nullable RequiresAuthority rule(Invocation<?> invocation) {
    return AnnotatedElementUtils.findMergedAnnotation(
        invocation.request().getClass(), RequiresAuthority.class);
  }

  /** Throws a 403 problem when the caller holds none of the required authorities. */
  static void check(RequiresAuthority rule, @Nullable Authentication auth, String operation) {
    Set<String> granted = authorities(auth);
    if (Arrays.stream(rule.value()).noneMatch(granted::contains)) {
      throw new AccessException(CoreErrors.FORBIDDEN, "missing authority for " + operation);
    }
  }

  static Set<String> authorities(@Nullable Authentication auth) {
    return auth == null || !auth.isAuthenticated()
        ? Set.of()
        : auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.toSet());
  }

  /** The caller as policies see it: identity from the request metadata, authorities from Spring. */
  static Caller caller(@Nullable Authentication auth, @Nullable RequestMetadata m) {
    Set<String> authorities = authorities(auth);
    if (m == null) {
      String name = auth == null || !auth.isAuthenticated() ? null : auth.getName();
      return new Caller(name, null, null, null, authorities);
    }
    return new Caller(m.subject(), m.clientId(), m.tenantId(), m.issuer(), authorities);
  }
}
