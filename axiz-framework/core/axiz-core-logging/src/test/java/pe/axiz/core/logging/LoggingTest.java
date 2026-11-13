package pe.axiz.core.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import pe.axiz.core.sensitive.ObjectMasker;

class LoggingTest {

  @Test
  void scopedMdcRestoresPreviousValues() {
    MDC.clear();
    MDC.put("k", "outer");
    try (ScopedMdc scope = ScopedMdc.of("k", "inner").with("other", "x").with("k", "again")) {
      assertEquals("again", MDC.get("k"));
      assertEquals("x", MDC.get("other"));
    }
    assertEquals("outer", MDC.get("k"), "the value before the first change comes back");
    assertNull(MDC.get("other"));
    try (ScopedMdc scope = ScopedMdc.of(Map.of("a", "1")).with("k", null)) {
      assertNull(MDC.get("k"));
      assertEquals("1", MDC.get("a"));
    }
    assertEquals("outer", MDC.get("k"));
    assertNull(MDC.get("a"));
    try (ScopedMdc scope = ScopedMdc.of("k", "line\nforged=entry")) {
      assertEquals("line_forged=entry", MDC.get("k"));
    }
    MDC.clear();
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
  void aBodyThatCannotBeMaskedWholeIsNotWritten() {
    HttpLogFormatter f = new HttpLogFormatter(List.of(), 64, ObjectMasker.defaults());
    String secret = UUID.randomUUID().toString();
    String cut = "{\"password\":\"" + secret;
    assertEquals(
        "<application/json, more than " + cut.length() + " chars: too large to mask, not logged>",
        f.body("application/json", cut, false));
    assertEquals(
        "<application/json, " + cut.length() + " chars: not a complete JSON value, not logged>",
        f.body("application/json", cut));
    assertFalse(
        f.body("application/json", "{\"\\u0070assword\":\"" + secret + "\"}").contains(secret));
    assertFalse(f.body("application/json", "{\"password\":[\"" + secret + "\"]}").contains(secret));
    String longSecret = "{\"password\":\"" + secret + "x".repeat(100) + "\"}";
    assertEquals("{\"password\":\"****\"}", f.body("application/json", longSecret));
    assertEquals(
        "<application/json, 70000 bytes: too large to mask, not logged>",
        f.omitted("application/json", 70_000));
    assertEquals("<unknown, 3 bytes: too large to mask, not logged>", f.omitted(null, 3));
  }

  @Test
  void entryMasksArgumentsAndSkipsThoseMarkedNoLog() {
    CapturedLogs.reset(Level.INFO);
    OperationLogger logger = new OperationLogger(ObjectMasker.defaults());
    logger.entry(
        "transfer",
        List.of("amount", "pin"),
        new Object[] {1, "4321", "extra"},
        new boolean[] {false, true},
        LogOperation.Level.INFO);

    String line = only().message();
    assertTrue(line.startsWith("operation=transfer phase=start args="), line);
    assertTrue(line.contains("amount=1"), line);
    assertTrue(line.contains("pin=<not logged>"), line);
    assertFalse(line.contains("4321"), line);
    assertTrue(line.contains("arg2="), "arguments without a name are numbered: " + line);
  }

  @Test
  void debugOperationsAreSilentUnlessDebugIsEnabled() {
    CapturedLogs.reset(Level.INFO);
    OperationLogger logger = new OperationLogger(ObjectMasker.defaults());
    logger.entry("op", LogOperation.Level.DEBUG);
    logger.entry("op", List.of("a"), new Object[] {1}, new boolean[0], LogOperation.Level.DEBUG);
    logger.success("op", Duration.ofMillis(5), "r", true, LogOperation.Level.DEBUG);
    assertEquals(List.of(), CapturedLogs.events());

    CapturedLogs.reset(Level.DEBUG);
    logger.entry("op", LogOperation.Level.DEBUG);
    logger.success("op", Duration.ofMillis(5), null, false, LogOperation.Level.DEBUG);
    List<CapturedLogs.Event> events = CapturedLogs.events();
    assertEquals(2, events.size());
    assertEquals(Level.DEBUG, events.get(0).level());
    assertEquals("operation=op phase=start", events.get(0).message());
    assertEquals("operation=op phase=end outcome=success", events.get(1).message());

    CapturedLogs.reset(Level.WARN);
    logger.entry("op", LogOperation.Level.INFO);
    logger.entry("op", List.of(), new Object[0], new boolean[0], LogOperation.Level.INFO);
    logger.success("op", Duration.ofMillis(5), "r", true, LogOperation.Level.INFO);
    assertEquals(List.of(), CapturedLogs.events(), "INFO lines are not written at WARN");
  }

  @Test
  void successCarriesOutcomeAndDurationAndMasksTheResult() {
    CapturedLogs.reset(Level.INFO);
    MDC.clear();
    new OperationLogger(ObjectMasker.defaults())
        .success(
            "login", Duration.ofMillis(42), Map.of("password", "x"), true, LogOperation.Level.INFO);

    CapturedLogs.Event e = only();
    assertEquals(Level.INFO, e.level());
    assertTrue(e.message().startsWith("operation=login phase=end outcome=success result="));
    assertFalse(e.message().contains("password=x"), e.message());
    assertEquals("success", e.mdc().get(LogFields.OUTCOME));
    assertEquals("42", e.mdc().get(LogFields.DURATION_MS));
    assertNull(MDC.get(LogFields.OUTCOME), "the scope is closed after the line");
  }

  @Test
  void failuresAreRejectionsAtWarnOrErrorsWithTheirStackTrace() {
    CapturedLogs.reset(Level.INFO);
    OperationLogger logger = new OperationLogger(ObjectMasker.defaults());
    IllegalStateException boom = new IllegalStateException("x");
    logger.failure("pay", Duration.ofMillis(5), boom, false, "AXZ-CORE-0500");
    logger.failure("pay", Duration.ofMillis(7), new IllegalArgumentException("y"), true, null);

    List<CapturedLogs.Event> events = CapturedLogs.events();
    assertEquals(2, events.size());
    CapturedLogs.Event error = events.get(0);
    assertEquals(Level.ERROR, error.level());
    assertEquals("operation=pay phase=end outcome=error", error.message());
    assertSame(boom, error.error());
    assertEquals("error", error.mdc().get(LogFields.OUTCOME));
    assertEquals("AXZ-CORE-0500", error.mdc().get(LogFields.ERROR_CODE));

    CapturedLogs.Event rejected = events.get(1);
    assertEquals(Level.WARN, rejected.level());
    assertEquals(
        "operation=pay phase=end outcome=rejected error=IllegalArgumentException",
        rejected.message());
    assertNull(rejected.error(), "a business rejection has no stack trace");
    assertEquals("rejected", rejected.mdc().get(LogFields.OUTCOME));
    assertFalse(rejected.mdc().containsKey(LogFields.ERROR_CODE));
    assertEquals("trace_id", LogFields.TRACE_ID);
  }

  private static CapturedLogs.Event only() {
    List<CapturedLogs.Event> events = CapturedLogs.events();
    assertEquals(1, events.size(), events.toString());
    return events.get(0);
  }
}
