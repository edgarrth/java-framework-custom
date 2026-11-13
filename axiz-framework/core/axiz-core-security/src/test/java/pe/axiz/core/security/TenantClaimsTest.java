package pe.axiz.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.tenancy.InvalidTenantException;

class TenantClaimsTest {
  private final ClaimsMapper mapper = new ClaimsMapper(ClaimMapping.defaults());

  private static Map<String, Object> identity() {
    return new HashMap<>(Map.of("iss", "https://issuer.test", "azp", "app", "sub", "user"));
  }

  @Test
  void absentAndValidTenantClaimsRemainDistinct() {
    var claims = identity();
    assertNull(mapper.map(claims).tenantId());
    claims.put("tenant", "valid-tenant");
    assertEquals("valid-tenant", mapper.map(claims).tenantId());
  }

  @Test
  void invalidFirstClaimCannotFallThroughToAnotherClaim() {
    for (Object value : List.of("", " ", "BAD/TENANT", 42, List.of("valid-tenant"))) {
      var claims = identity();
      claims.put("tenant", value);
      claims.put("tid", "valid-tenant");
      var failure = assertThrows(InvalidTenantException.class, () -> mapper.map(claims));
      assertEquals("Invalid tenant claim", failure.getMessage());
    }
  }

  @Test
  void presentNullClaimCannotBecomeAnAbsentClaim() {
    var claims = identity();
    claims.put("tenant", null);
    claims.put("tid", "valid-tenant");
    assertThrows(InvalidTenantException.class, () -> mapper.map(claims));
  }
}
