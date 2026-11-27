package pe.axiz.extension.cqrs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.AccessException;
import pe.axiz.core.errors.CoreErrors;

class AccessPoliciesTest {

  record Debit(String customerId, String account) implements Command<String> {}

  @RequiresAuthority("SCOPE_ops")
  record Close(String id) implements Command<String> {}

  @OpenAccess(reason = "public catalog")
  record Catalog() implements Query<String> {}

  record Unprotected(String id) implements Command<String> {}

  /**
   * Los clientes actúan solo sobre sus propios recursos; los servicios necesitan un scope
   * explícito.
   */
  static final class DebitPolicy implements AccessPolicy<Debit> {
    @Override
    public Class<Debit> requestType() {
      return Debit.class;
    }

    @Override
    public void check(Debit request, Caller caller) {
      boolean allowed =
          caller.isMachine()
              ? caller.hasAuthority("SCOPE_payments:write")
              : caller.isUser(request.customerId());
      if (!allowed) {
        throw new AccessException(CoreErrors.FORBIDDEN, "not the owner");
      }
    }
  }

  private final AccessPolicies policies = new AccessPolicies(List.of(new DebitPolicy()));

  @Test
  void aCustomerCannotActOnAnotherCustomersResources() {
    Caller ana = new Caller("ana", "web", null, "iss", Set.of());
    assertDoesNotThrow(() -> policies.check(new Debit("ana", "acc-1"), ana));
    assertThrows(AccessException.class, () -> policies.check(new Debit("luis", "acc-2"), ana));
  }

  @Test
  void servicesNeedTheirScope() {
    Caller process = new Caller(null, "payment-process", null, "iss", Set.of());
    assertThrows(AccessException.class, () -> policies.check(new Debit("ana", "acc"), process));
    Caller writer = new Caller("svc", "svc", null, "iss", Set.of("SCOPE_payments:write"));
    assertTrue(writer.isMachine());
    assertDoesNotThrow(() -> policies.check(new Debit("ana", "acc"), writer));
    assertFalse(Caller.anonymous().authenticated());
  }

  @Test
  void requestsWithoutAnyProtectionAreReported() {
    assertEquals(
        List.of(Unprotected.class),
        policies.unprotected(List.of(Debit.class, Close.class, Catalog.class, Unprotected.class)));
    assertFalse(policies.isEmpty());
    assertTrue(AccessPolicies.none().isEmpty());
    assertTrue(policies.covers(Debit.class));
    assertFalse(AccessPolicies.none().covers(Debit.class));
  }
}
