package pe.axiz.extension.cqrs.spring;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.errors.AccessException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.extension.cqrs.AccessPolicies;
import pe.axiz.extension.cqrs.Caller;
import pe.axiz.extension.cqrs.Invocation;
import pe.axiz.extension.cqrs.PipelineBehavior;
import pe.axiz.extension.cqrs.RequiresAuthority;

/**
 * Etapa de autorización del pipeline: {@link RequiresAuthority} en el tipo de petición (403 cuando
 * quien llama no la tiene) y luego cada {@code AccessPolicy} coincidente (verificaciones a nivel de
 * objeto). Se ejecuta antes de la idempotencia, de modo que un resultado almacenado nunca se
 * devuelve a un llamador que podría no estar autorizado a enviar la petición.
 */
public final class AuthorizationBehavior implements PipelineBehavior {

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

  /** Lanza un problema 403 cuando quien llama no tiene ninguna de las autoridades requeridas. */
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

  /**
   * Quien llama tal como lo ven las políticas: identidad a partir de los metadatos de la petición y
   * autoridades a partir de Spring.
   */
  static Caller caller(@Nullable Authentication auth, @Nullable RequestMetadata m) {
    Set<String> authorities = authorities(auth);
    if (m == null) {
      String name = auth == null || !auth.isAuthenticated() ? null : auth.getName();
      return new Caller(name, null, null, null, authorities);
    }
    return new Caller(m.subject(), m.clientId(), m.tenantId(), m.issuer(), authorities);
  }
}
