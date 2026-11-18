package pe.axiz.spring.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.errors.ValidationException;
import pe.axiz.core.tenancy.TenantContext;

class TenancyTest {

  private static AxizTenancyProperties props(boolean trustHeader) {
    return new AxizTenancyProperties(
        true, "X-Tenant-Id", trustHeader, true, List.of("/actuator/**"));
  }

  @Test
  void tokenTenantIsBoundAndHeaderIgnoredUnlessTrusted() throws Exception {
    TenantFilter filter = new TenantFilter(new TenantFilterSupport(props(false)));
    RequestMetadata m =
        RequestMetadata.builder("corr-12345678", "req-12345678").tenantId("bank-a").build();
    AtomicReference<String> seen = new AtomicReference<>();
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/accounts");
    req.addHeader("X-Tenant-Id", "bank-b");
    try (RequestContext.Scope ignored = RequestContext.open(m)) {
      filter.doFilter(
          req, new MockHttpServletResponse(), (rq, rs) -> seen.set(TenantContext.require()));
    }
    assertEquals("bank-a", seen.get());
    assertTrue(TenantContext.current().isEmpty());

    MockHttpServletRequest noTenant = new MockHttpServletRequest("GET", "/accounts");
    noTenant.addHeader("X-Tenant-Id", "bank-b");
    assertThrows(
        ValidationException.class,
        () -> filter.doFilter(noTenant, new MockHttpServletResponse(), (rq, rs) -> {}));
  }

  @Test
  void trustedHeaderAndExcludedPaths() {
    TenantFilterSupport s = new TenantFilterSupport(props(true));
    assertEquals("bank-b", s.resolve(null, h -> "bank-b").orElseThrow());
    assertTrue(s.resolve(null, h -> "Bad Tenant!").isEmpty());
    assertTrue(s.skip("/actuator/health"));
    assertFalse(s.skip("/accounts"));
  }
}
