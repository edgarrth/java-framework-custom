package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ServiceRolesTest {
  @Test
  void rolesRequireMeaningfulServiceNames() {
    assertTrue(ServiceRoles.validName("business", "payment-orders"));
    assertTrue(ServiceRoles.validName("core", "banking-core"));
    assertTrue(ServiceRoles.validName("gateway", "payment-network-gateway"));
    assertTrue(ServiceRoles.validName("ux", "payments-ux"));
    assertTrue(ServiceRoles.validName("support", "otp-support"));
    assertTrue(ServiceRoles.validName("event-processor", "notifications-processor"));
    assertTrue(ServiceRoles.validName("batch", "settlement-batch"));
    for (String name : List.of("payments-business", "beneficiaries-crud", "payments-hexagonal"))
      assertFalse(ServiceRoles.validName("business", name));
    assertFalse(ServiceRoles.validName("gateway", "external"));
    assertFalse(ServiceRoles.validName("core", "core"));
  }

  @Test
  void channelAndBoundaryServicesCannotBypassBusinessRules() {
    assertDoesNotThrow(() -> ServiceRoles.requireCall("ux", "business"));
    assertDoesNotThrow(() -> ServiceRoles.requireCall("business", "gateway"));
    assertDoesNotThrow(() -> ServiceRoles.requireCall("gateway", "external-system"));
    assertDoesNotThrow(() -> ServiceRoles.requireCall("core", "internal-system"));
    assertDoesNotThrow(() -> ServiceRoles.requireCall("support", "commodity-provider"));
    for (String target : List.of("core", "gateway", "external-system", "ux"))
      assertThrows(IllegalArgumentException.class, () -> ServiceRoles.requireCall("ux", target));
    assertThrows(
        IllegalArgumentException.class, () -> ServiceRoles.requireCall("support", "business"));
    assertThrows(IllegalArgumentException.class, () -> ServiceRoles.requireCall("core", "gateway"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ServiceRoles.requireCall("business", "external-system"));
  }
}
