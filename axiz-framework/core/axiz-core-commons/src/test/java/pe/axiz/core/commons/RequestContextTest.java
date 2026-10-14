package pe.axiz.core.commons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RequestContextTest {

  private static RequestMetadata meta(String id) {
    return RequestMetadata.builder(id + "-correlation", id + "-request").build();
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
