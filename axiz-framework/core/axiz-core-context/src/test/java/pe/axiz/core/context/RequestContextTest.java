package pe.axiz.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RequestContextTest {

  private static RequestMetadata meta(String id) {
    return RequestMetadata.builder(id + "-correlation", id + "-request").build();
  }

  @Test
  void callerEnrichmentPreservesTheRequestAndCopiesMutableAttributes() {
    var attributes = new HashMap<>(Map.of("business-channel", "retail"));
    var received = Instant.parse("2026-11-17T00:00:00Z");
    var builder =
        RequestMetadata.builder("correlation", "request")
            .channel("mobile")
            .clientId("untrusted-client")
            .subject("untrusted-user")
            .tenantId("routed-tenant")
            .issuer("issuer-original")
            .locale(Locale.forLanguageTag("es-PE"))
            .receivedAt(received)
            .attributes(attributes);
    attributes.put("business-channel", "modified");
    var original = builder.build();
    var authenticated =
        original.withCaller(
            "verified-client", "verified-user", "verified-tenant", "trusted-issuer");
    assertEquals(
        new RequestMetadata(
            "correlation",
            "request",
            "mobile",
            "verified-client",
            "verified-user",
            "verified-tenant",
            "trusted-issuer",
            Locale.forLanguageTag("es-PE"),
            received,
            Map.of("business-channel", "retail")),
        authenticated);
    assertEquals("untrusted-user", original.subject());
    assertEquals("routed-tenant", original.withCaller("machine", null, null).tenantId());
    assertEquals("issuer-original", original.withCaller("machine", null, null).issuer());
    assertNull(original.withCaller("machine", null, null).subject());
    assertEquals(authenticated.withTenant("other").withTenant("verified-tenant"), authenticated);
    assertEquals("retail", original.attribute("business-channel").orElseThrow());
    assertTrue(original.attribute("absent").isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> original.attributes().put("x", "y"));
    assertThrows(
        IllegalArgumentException.class, () -> RequestMetadata.builder(" ", "request").build());
    assertThrows(
        IllegalArgumentException.class, () -> RequestMetadata.builder("correlation", " ").build());
  }

  @Test
  void concurrentTenantsCannotInheritEachOthersContextAndFailuresAlwaysRestoreIt()
      throws Exception {
    try (var parent = RequestContext.open(meta("parent"));
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var ready = new CountDownLatch(32);
      var release = new CountDownLatch(1);
      var tasks = new ArrayList<Future<Void>>();
      for (int i = 0; i < 32; i++) {
        var metadata = meta("tenant-" + i);
        tasks.add(
            executor.submit(
                () -> {
                  assertNull(RequestContext.get());
                  try (var scope = RequestContext.open(metadata)) {
                    ready.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    assertEquals(metadata, RequestContext.get());
                    assertThrows(
                        IllegalStateException.class,
                        () ->
                            RequestContext.call(
                                meta("nested"),
                                () -> {
                                  throw new IllegalStateException("synthetic");
                                }));
                    assertEquals(metadata, RequestContext.get());
                  }
                  assertNull(RequestContext.get());
                  return null;
                }));
      }
      try {
        assertTrue(ready.await(5, TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }
      for (var task : tasks) task.get(5, TimeUnit.SECONDS);
      assertEquals(meta("parent"), RequestContext.get());
    }
    assertNull(RequestContext.get());
  }

  @Test
  void scopesNestAndRestore() throws Exception {
    assertTrue(RequestContext.current().isEmpty());
    try (RequestContext.Scope outer = RequestContext.open(meta("outer"))) {
      try (RequestContext.Scope inner = RequestContext.open(meta("inner"))) {
        assertEquals("inner-correlation", RequestContext.current().orElseThrow().correlationId());
      }
      assertEquals("outer-correlation", RequestContext.current().orElseThrow().correlationId());
      assertEquals(
          "x-request",
          RequestContext.call(meta("x"), () -> RequestContext.current().orElseThrow().requestId()));
    }
    assertTrue(RequestContext.current().isEmpty());
  }
}
