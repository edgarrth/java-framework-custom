package pe.axiz.spring.idempotency;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.StandardServletAsyncWebRequest;
import org.springframework.web.context.request.async.WebAsyncUtils;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.idempotency.IdempotencyRecord;
import pe.axiz.core.idempotency.IdempotencyService;
import pe.axiz.core.idempotency.InMemoryIdempotencyStore;

class ServletIdempotencySafetyTest {
  @Test
  void asynchronousCompletionTimeoutAndErrorKeepAConsistentOutcomeAcrossRepeatedCallbacks()
      throws Exception {
    for (int outcome = 0; outcome < 3; outcome++) {
      var store = new InMemoryIdempotencyStore();
      var service =
          new IdempotencyService(
              store, Duration.ofSeconds(30), Duration.ofHours(1), Clock.systemUTC());
      var filter = new IdempotencyFilter(service, 1024, 1024);
      var request = new MockHttpServletRequest("POST", "/payments");
      request.addHeader("Idempotency-Key", "async-key-0000001");
      request.setAsyncSupported(true);
      var response = new MockHttpServletResponse();
      var context = new AtomicReference<MockAsyncContext>();
      filter.doFilter(
          request,
          response,
          (req, res) -> {
            var execution =
                (IdempotencyService.Proceed)
                    service.begin("scope", "async-key-0000001", "fingerprint");
            req.setAttribute(IdempotencySupport.KEY_ATTRIBUTE, execution);
            req.setAttribute(IdempotencyFilter.LEASE_ATTRIBUTE, service.startLease(execution));
            ((HttpServletResponse) res).setStatus(201);
            res.getOutputStream().write("accepted".getBytes(StandardCharsets.UTF_8));
            var asyncManager = WebAsyncUtils.getAsyncManager(req);
            asyncManager.setAsyncWebRequest(
                new StandardServletAsyncWebRequest(
                    (HttpServletRequest) req, (HttpServletResponse) res));
            try {
              asyncManager.startDeferredResultProcessing(new DeferredResult<>());
            } catch (Exception failure) {
              throw new ServletException(failure);
            }
            context.set((MockAsyncContext) ((HttpServletRequest) req).getAsyncContext());
          });
      var async = context.get();
      assertEquals(2, async.getListeners().size());
      var listener = async.getListeners().getLast();
      var next = new MockAsyncContext(request, response);
      listener.onStartAsync(new AsyncEvent(next));
      assertEquals(1, next.getListeners().size());
      if (outcome == 0) listener.onComplete(new AsyncEvent(async));
      else if (outcome == 1) listener.onTimeout(new AsyncEvent(async));
      else listener.onError(new AsyncEvent(async));
      listener.onComplete(new AsyncEvent(async));
      var record = store.find("scope:async-key-0000001", Clock.systemUTC().instant()).orElseThrow();
      assertEquals(
          outcome == 0 ? IdempotencyRecord.Status.COMPLETED : IdempotencyRecord.Status.UNKNOWN,
          record.status());
      if (outcome == 0) {
        var replay =
            assertInstanceOf(
                IdempotencyService.Replay.class,
                service.begin("scope", "async-key-0000001", "fingerprint"));
        assertArrayEquals("accepted".getBytes(StandardCharsets.UTF_8), replay.response().body());
      } else
        assertThrows(
            BusinessException.class,
            () -> service.begin("scope", "async-key-0000001", "fingerprint"));
    }
  }

  @Test
  void fingerprintedBodyCanBeReadAsUtf8MoreThanOnceWithoutSharingMutableStorage() throws Exception {
    byte[] bytes = "acción".getBytes(StandardCharsets.UTF_8);
    var cached = new CachedBodyRequest(new MockHttpServletRequest(), bytes);
    bytes[0] = 0;
    assertEquals("acción", cached.getReader().readLine());
    try (var stream = cached.getInputStream()) {
      assertTrue(stream.isReady());
      assertArrayEquals("acción".getBytes(StandardCharsets.UTF_8), stream.readAllBytes());
      assertTrue(stream.isFinished());
      assertThrows(UnsupportedOperationException.class, () -> stream.setReadListener(null));
    }
    assertEquals(7, cached.getContentLength());
    assertEquals(7L, cached.getContentLengthLong());
    var detached = cached.body();
    detached[0] = 0;
    assertEquals("acción", cached.getReader().readLine());
  }

  @Test
  void retentionAndBodyLimitsRejectUnsafeConfigurationAtTheirExactBoundaries() {
    Duration lease = Duration.ofSeconds(30);
    Duration window = Duration.ofHours(1);
    Duration retention = window.plus(lease);
    new AxizIdempotencyProperties("memory", "table", "prefix", lease, retention, 1, 1, window);
    for (Duration invalid : new Duration[] {Duration.ZERO, retention.minusNanos(1)})
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new AxizIdempotencyProperties(
                  "memory", "table", "prefix", lease, invalid, 1, 1, window));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AxizIdempotencyProperties(
                "memory", "table", "prefix", Duration.ZERO, retention, 1, 1, window));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AxizIdempotencyProperties(
                "memory", "table", "prefix", lease, retention, 1, 1, window.negated()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AxizIdempotencyProperties(
                "memory", "table", "prefix", lease, retention, 0, 1, window));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AxizIdempotencyProperties(
                "memory", "table", "prefix", lease, retention, 1, 0, window));
  }
}
