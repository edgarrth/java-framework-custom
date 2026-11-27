package pe.axiz.extension.cqrs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Políticas de acceso de una aplicación: ejecuta las que coinciden con una petición e informa los
 * tipos de petición que nada protege.
 */
public final class AccessPolicies {

  private final List<AccessPolicy<?>> policies;

  public AccessPolicies(Collection<? extends AccessPolicy<?>> policies) {
    this.policies = List.copyOf(Objects.requireNonNull(policies));
  }

  /** Indica si no hay políticas imperativas configuradas (los buses reactivos las rechazan). */
  public boolean isEmpty() {
    return policies.isEmpty();
  }

  public static AccessPolicies none() {
    return new AccessPolicies(List.of());
  }

  /** Ejecuta cada política cuyo tipo de petición coincide; se propaga la primera denegación. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public void check(Object request, Caller caller) {
    for (AccessPolicy policy : policies) {
      if (policy.requestType().isInstance(request)) {
        policy.check(request, caller);
      }
    }
  }

  public boolean covers(Class<?> requestType) {
    return policies.stream().anyMatch(p -> p.requestType().isAssignableFrom(requestType));
  }

  /**
   * Tipos de petición que nada protege: sin {@link RequiresAuthority}, sin {@link OpenAccess} y sin
   * política coincidente. Ordenados por nombre.
   */
  public List<Class<?>> unprotected(Collection<Class<?>> requestTypes) {
    List<Class<?>> out = new ArrayList<>();
    for (Class<?> type : requestTypes) {
      if (!type.isAnnotationPresent(RequiresAuthority.class)
          && !type.isAnnotationPresent(OpenAccess.class)
          && !covers(type)) {
        out.add(type);
      }
    }
    out.sort(Comparator.comparing(Class::getName));
    return out;
  }
}
