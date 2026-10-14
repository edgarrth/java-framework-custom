package pe.axiz.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class TenancyTest {

  @Test
  void bindsAndRestores() throws Exception {
    assertTrue(TenantContext.current().isEmpty());
    String inner =
        TenantContext.callAs(
            "tenant-a",
            () ->
                TenantContext.callAs("tenant-b", TenantContext::require)
                    + "/"
                    + TenantContext.require());
    assertEquals("tenant-b/tenant-a", inner);
    assertTrue(TenantContext.current().isEmpty());
    assertThrows(TenantRequiredException.class, TenantContext::require);
    assertThrows(IllegalArgumentException.class, () -> TenantContext.callAs("Bad Tenant", () -> 1));
    assertThrows(
        IllegalStateException.class,
        () ->
            TenantContext.runAs(
                "t",
                () -> {
                  throw new IllegalStateException("x");
                }));
    assertTrue(TenantContext.current().isEmpty());
  }

  @Test
  void propagatesToOtherThreads() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      String[] seen = new String[1];
      TenantContext.runAs(
          "t1",
          () -> {
            Runnable task = TenantContext.propagate(() -> seen[0] = TenantContext.require());
            try {
              Future<?> f = executor.submit(task);
              f.get();
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          });
      assertEquals("t1", seen[0]);
      Runnable plain = () -> {};
      assertEquals(plain, TenantContext.propagate(plain));
    }
  }

  @Test
  void resolverPrefersTokenAndTrustsHeaderOnlyWhenConfigured() {
    Map<String, String> headers = Map.of("X-Tenant-Id", "from-header");
    assertEquals(
        Optional.of("from-token"),
        new TenantResolver(true, "X-Tenant-Id").resolve("from-token", headers::get));
    assertEquals(
        Optional.of("from-header"),
        new TenantResolver(true, "X-Tenant-Id").resolve(null, headers::get));
    assertEquals(
        Optional.empty(), new TenantResolver(false, "X-Tenant-Id").resolve(null, headers::get));
    assertEquals(
        Optional.empty(),
        new TenantResolver(true, "X-Tenant-Id").resolve("INVALID!", h -> "bad value"));
  }
}
