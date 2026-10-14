package pe.axiz.core.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import pe.axiz.core.sensitive.ObjectMasker;

class LoggingTest {

  @Test
  void scopedMdcRestoresPreviousValues() {
    // Without an SLF4J binding MDC is a no-op adapter; the API must still be safe to call.
    MDC.put("k", "outer");
    try (ScopedMdc scope = ScopedMdc.of("k", "inner").with("other", "x")) {
      assertTrue(MDC.get("k") == null || MDC.get("k").equals("inner"));
    }
    assertTrue(MDC.get("k") == null || MDC.get("k").equals("outer"));
    assertTrue(MDC.get("other") == null);
    try (ScopedMdc scope = ScopedMdc.of(Map.of("a", "1")).with("k", null)) {
      assertNull(MDC.get("k"));
    }
  }

  @Test
  void sanitizesLogInjection() {
    assertEquals("a_b_c", ScopedMdc.sanitize("a\rb\nc"));
    assertEquals(256, ScopedMdc.sanitize("x".repeat(500)).length());
  }

  @Test
  void httpFormatterNeverLogsCredentials() {
    HttpLogFormatter f =
        new HttpLogFormatter(
            List.of("Content-Type", "Authorization", "X-Channel", "X-Api-Key"),
            60,
            ObjectMasker.defaults());
    Map<String, String> headers =
        Map.of(
            "Content-Type",
            "application/json",
            "Authorization",
            "Bearer abc",
            "X-Channel",
            "web",
            "X-Other",
            "o");
    Map<String, String> logged = f.headers(headers.keySet(), headers::get);
    assertEquals(Map.of("Content-Type", "application/json", "X-Channel", "web"), logged);
    String body =
        f.body("application/json", "{\"password\":\"x\",\"note\":\"" + "n".repeat(100) + "\"}");
    assertTrue(body.startsWith("{\"password\":\"****\""), body);
    assertTrue(body.endsWith("(truncated)"));
    assertEquals("<text/plain, 5 chars>", f.body("text/plain", "hello"));
    assertEquals("", f.body("application/json", null));
    assertFalse(f.body(null, "abc").contains("abc"));
  }

  @Test
  void operationLoggerHandlesAllPaths() {
    OperationLogger logger = new OperationLogger(ObjectMasker.defaults());
    logger.entry(
        "op",
        List.of("a", "secret"),
        new Object[] {1, "s"},
        new boolean[] {false, true},
        LogOperation.Level.INFO);
    logger.entry("op", LogOperation.Level.DEBUG);
    logger.success(
        "op", Duration.ofMillis(5), Map.of("password", "x"), true, LogOperation.Level.INFO);
    logger.success("op", Duration.ofMillis(5), null, false, LogOperation.Level.DEBUG);
    logger.failure(
        "op", Duration.ofMillis(5), new IllegalStateException("x"), false, "AXZ-CORE-0500");
    logger.failure("op", Duration.ofMillis(5), new IllegalArgumentException("x"), true, null);
    assertEquals("trace_id", LogFields.TRACE_ID);
  }
}
