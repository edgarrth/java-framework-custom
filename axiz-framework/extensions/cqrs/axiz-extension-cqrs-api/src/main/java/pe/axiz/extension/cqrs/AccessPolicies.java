package pe.axiz.extension.cqrs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The access policies of an application: runs the ones matching a request and reports request types
 * that nothing protects.
 */
public final class AccessPolicies {

  private final List<AccessPolicy<?>> policies;

  public AccessPolicies(Collection<? extends AccessPolicy<?>> policies) {
    this.policies = List.copyOf(Objects.requireNonNull(policies));
  }

  /** Whether no imperative policies are configured (reactive buses reject them). */
  public boolean isEmpty() {
    return policies.isEmpty();
  }

  public static AccessPolicies none() {
    return new AccessPolicies(List.of());
  }

  /** Runs every policy whose request type matches; the first denial propagates. */
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
   * Request types protected by nothing: no {@link RequiresAuthority}, no {@link OpenAccess} and no
   * matching policy. Sorted by name.
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
