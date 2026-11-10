package pe.axiz.spring.idempotency;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.idempotency.AsyncIdempotencyService;
import pe.axiz.core.idempotency.IdempotencyErrors;
import pe.axiz.core.idempotency.IdempotencyService;
import pe.axiz.core.idempotency.Idempotent;
import pe.axiz.core.idempotency.InMemoryAsyncIdempotencyStore;
import pe.axiz.core.idempotency.InMemoryIdempotencyStore;
import pe.axiz.core.idempotency.StoredResponse;
import reactor.core.publisher.Mono;

class IdempotencyTest {

  /** Stands in for method security ({@code @PreAuthorize}): ordered before the advice. */
  static final class Denied extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  /** What the simulated handler does once it runs. */
  @FunctionalInterface
  interface Handler {
    void run(HttpServletResponse response, int execution) throws Exception;
  }

  public static class PaymentsController {
    final AtomicInteger executions = new AtomicInteger();
    Handler handler =
        (res, n) -> {
          res.setStatus(201);
          res.getWriter().write("{\"id\":\"p-" + n + "\"}");
        };

    @Idempotent
    public void pay(HttpServletResponse response) throws Exception {
      handler.run(response, executions.incrementAndGet());
    }
  }

  public static class ReactivePaymentsController {
    final AtomicInteger executions = new AtomicInteger();

    @Idempotent
    public Mono<String> pay() {
      return Mono.fromSupplier(() -> "p-" + executions.incrementAndGet());
    }
  }

  private final IdempotencyService service =
      new IdempotencyService(
          new InMemoryIdempotencyStore(),
          Duration.ofSeconds(30),
          Duration.ofHours(1),
          Clock.systemUTC());
  private final AtomicBoolean allowed = new AtomicBoolean(true);
  private final PaymentsController controller = new PaymentsController();
  private final PaymentsController proxy = proxy(controller);

  private PaymentsController proxy(PaymentsController target) {
    ProxyFactory factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAdvice(
        (MethodInterceptor)
            inv -> {
              if (!allowed.get()) {
                throw new Denied();
              }
              return inv.proceed();
            });
    factory.addAdvice(new IdempotencyMethodInterceptor(() -> service));
    return (PaymentsController) factory.getProxy();
  }

  private MockHttpServletResponse call(String key, String body) throws Exception {
    return call(key, body, null, null, Integer.MAX_VALUE);
  }

  /** Filter, then the "dispatcher": the proxied handler and the replay exception handler. */
  private MockHttpServletResponse call(
      String key,
      String body,
      @Nullable String query,
      @Nullable RequestMetadata caller,
      int maxResponseSize)
      throws Exception {
    IdempotencyFilter filter = new IdempotencyFilter(service, 1024, maxResponseSize);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/payments");
    request.setQueryString(query);
    request.addHeader("Idempotency-Key", key);
    request.setContent(body.getBytes(StandardCharsets.UTF_8));
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(
        request,
        response,
        (req, res) -> {
          RequestContextHolder.setRequestAttributes(
              new ServletRequestAttributes((HttpServletRequest) req, (HttpServletResponse) res));
          try (RequestContext.Scope ignored =
              RequestContext.open(caller != null ? caller : metadata("ana"))) {
            proxy.pay((HttpServletResponse) res);
          } catch (IdempotentReplay replay) {
            write(new IdempotencyReplayHandler().replay(replay), (HttpServletResponse) res);
          } catch (RuntimeException e) {
            throw e;
          } catch (Exception e) {
            throw new IllegalStateException(e);
          } finally {
            RequestContextHolder.resetRequestAttributes();
          }
        });
    return response;
  }

  private static void write(ResponseEntity<byte[]> entity, HttpServletResponse res)
      throws IOException {
    res.setStatus(entity.getStatusCode().value());
    entity.getHeaders().forEach((k, v) -> res.setHeader(k, v.get(0)));
    res.getOutputStream().write(entity.getBody() == null ? new byte[0] : entity.getBody());
  }

  private static RequestMetadata metadata(@Nullable String subject) {
    return RequestMetadata.builder("corr-1", "req-1")
        .clientId("app")
        .subject(subject)
        .tenantId("t1")
        .issuer("https://issuer.example")
        .locale(Locale.ROOT)
        .receivedAt(Instant.EPOCH)
        .build();
  }

  @Test
  void sameKeyReplaysAndDifferentBodyIsRejected() throws Exception {
    MockHttpServletResponse first = call("idempotency-key-00000001", "{\"amount\":10}");
    MockHttpServletResponse second = call("idempotency-key-00000001", "{\"amount\":10}");
    assertEquals(1, controller.executions.get());
    assertEquals(201, second.getStatus());
    assertEquals(first.getContentAsString(), second.getContentAsString());
    assertEquals("true", second.getHeader(IdempotencyReplayHandler.REPLAYED_HEADER));
    BusinessException reused =
        assertThrows(
            BusinessException.class, () -> call("idempotency-key-00000001", "{\"amount\":99}"));
    assertEquals(422, reused.category().httpStatus());
  }

  @Test
  void aKeyOutsideTheContractIsRejectedBeforeTheHandlerRuns() {
    // 16 to 128 characters of [A-Za-z0-9._:-]: too short, too long and unsafe characters
    for (String key : new String[] {"key-00000001", "k".repeat(129), "key with spaces 0001"}) {
      BusinessException e = assertThrows(BusinessException.class, () -> call(key, "{}"));
      assertEquals(400, e.category().httpStatus(), key);
    }
    assertEquals(0, controller.executions.get());
  }

  /**
   * Regression: the first request was allowed, the caller's permission was then revoked; repeating
   * it with the same key must be denied without returning the stored body.
   */
  @Test
  void aReplayIsDeniedWhenTheCallerIsNoLongerAuthorized() throws Exception {
    MockHttpServletResponse first = call("idempotency-key-00000010", "{}");
    assertEquals(201, first.getStatus());
    allowed.set(false);
    MockHttpServletResponse[] holder = new MockHttpServletResponse[1];
    assertThrows(Denied.class, () -> holder[0] = call("idempotency-key-00000010", "{}"));
    assertNull(holder[0]);
    assertEquals(1, controller.executions.get());
    allowed.set(true);
    MockHttpServletResponse replay = call("idempotency-key-00000010", "{}");
    assertEquals(first.getContentAsString(), replay.getContentAsString());
    assertEquals("true", replay.getHeader(IdempotencyReplayHandler.REPLAYED_HEADER));
  }

  /** Regression: an exception escaping the handler is not stored as a 200. */
  @Test
  void anExceptionEscapingTheHandlerPreservesAnUncertainOperation() throws Exception {
    controller.handler =
        (res, n) -> {
          throw new IllegalStateException("downstream timeout");
        };
    assertThrows(IllegalStateException.class, () -> call("idempotency-key-00000002", "{}"));
    controller.handler =
        (res, n) -> {
          res.setStatus(201);
          res.getWriter().write("ok-" + n);
        };
    BusinessException uncertain =
        assertThrows(BusinessException.class, () -> call("idempotency-key-00000002", "{}"));
    assertEquals(IdempotencyErrors.OUTCOME_UNKNOWN.code(), uncertain.code());
    assertEquals(1, controller.executions.get());
  }

  /**
   * A server error (a transient technical failure: timeout of the database, unavailable downstream)
   * is never stored: the key is released and a retry with the same key runs again.
   */
  @Test
  void aServerErrorDoesNotPermitAnotherExecution() throws Exception {
    controller.handler =
        (res, n) -> {
          res.setStatus(503);
          res.getWriter().write("{\"title\":\"unavailable\"}");
        };
    assertEquals(503, call("idempotency-key-00000050", "{}").getStatus());
    controller.handler =
        (res, n) -> {
          res.setStatus(201);
          res.getWriter().write("{\"id\":\"p-" + n + "\"}");
        };
    BusinessException uncertain =
        assertThrows(BusinessException.class, () -> call("idempotency-key-00000050", "{}"));
    assertEquals(IdempotencyErrors.OUTCOME_UNKNOWN.code(), uncertain.code());
    assertEquals(1, controller.executions.get());
  }

  /**
   * A client error is a decision about that exact request: it is stored and replayed, so the same
   * key with the same body gets the same answer and the operation never runs twice.
   */
  @Test
  void aClientErrorIsStoredAndReplayed() throws Exception {
    controller.handler =
        (res, n) -> {
          res.setStatus(422);
          res.getWriter().write("{\"title\":\"limit exceeded\"}");
        };
    assertEquals(422, call("idempotency-key-00000051", "{}").getStatus());
    MockHttpServletResponse replay = call("idempotency-key-00000051", "{}");
    assertEquals(1, controller.executions.get(), "the business rejection is not re-executed");
    assertEquals(422, replay.getStatus());
    assertEquals("true", replay.getHeader(IdempotencyReplayHandler.REPLAYED_HEADER));
  }

  /** Regressions: the query string is part of the fingerprint, taken as sent. */
  @Test
  void theQueryStringAsSentIsPartOfTheFingerprint() throws Exception {
    call("idempotency-key-00000003", "{}", "sort=a&sort=b", null, Integer.MAX_VALUE);
    BusinessException reordered =
        assertThrows(
            BusinessException.class,
            () -> call("idempotency-key-00000003", "{}", "sort=b&sort=a", null, Integer.MAX_VALUE));
    assertEquals(422, reordered.category().httpStatus());
    assertEquals(1, controller.executions.get());
  }

  /** Regression: two callers sharing a key never see each other's responses. */
  @Test
  void keysAreScopedByCaller() throws Exception {
    MockHttpServletResponse alice =
        call("idempotency-key-00000004", "{}", null, metadata("alice"), Integer.MAX_VALUE);
    MockHttpServletResponse bob =
        call("idempotency-key-00000004", "{}", null, metadata("bob"), Integer.MAX_VALUE);
    assertEquals(2, controller.executions.get());
    assertNotEquals(alice.getContentAsString(), bob.getContentAsString());
    assertNull(bob.getHeader(IdempotencyReplayHandler.REPLAYED_HEADER));
    assertTrue(
        IdempotencySupport.operation(
                PaymentsController.class.getMethod("pay", HttpServletResponse.class),
                PaymentsController.class)
            .endsWith("PaymentsController#pay"));
  }

  /** Regression: large responses are streamed whole but never fully buffered. */
  @Test
  void anOversizedResponseIsReplayedWithoutBodyAndNeverBufferedWhole() throws Exception {
    controller.handler =
        (res, n) -> {
          res.setStatus(201);
          res.setHeader("Location", "/payments/1");
          res.getOutputStream().write(new byte[64 * 1024]);
        };
    MockHttpServletResponse first = call("idempotency-key-00000005", "{}", null, null, 16);
    assertEquals(64 * 1024, first.getContentAsByteArray().length, "the client gets all of it");
    MockHttpServletResponse replay = call("idempotency-key-00000005", "{}", null, null, 16);
    assertEquals(1, controller.executions.get());
    assertEquals(201, replay.getStatus());
    assertEquals("/payments/1", replay.getHeader("Location"));
    assertEquals("true", replay.getHeader(StoredResponse.BODY_OMITTED_HEADER));
    assertEquals(0, replay.getContentAsByteArray().length);

    CapturingResponse capturing = new CapturingResponse(new MockHttpServletResponse(), 16);
    for (int i = 0; i < 1024; i++) {
      capturing.getOutputStream().write(new byte[1024]);
    }
    assertTrue(capturing.capture().overflowed());
    assertEquals(0, capturing.capture().buffered(), "memory does not grow with the response");
  }

  @Test
  void boundedCaptureKeepsSmallBodies() {
    BoundedCapture capture = new BoundedCapture(4);
    capture.write(new byte[] {1, 2});
    capture.write(new byte[] {3, 4});
    assertArrayEquals(new byte[] {1, 2, 3, 4}, capture.bytes());
    capture.write(new byte[] {5});
    assertTrue(capture.overflowed());
    assertEquals(
        Map.of("Location", "/x", StoredResponse.BODY_OMITTED_HEADER, "true"),
        IdempotencySupport.stored(201, Map.of("Location", "/x"), new byte[8], 4).headers());
  }

  /** Regression on WebFlux: the decision is taken inside reactive method security. */
  @Test
  void reactiveReplaysAlsoPassAuthorizationFirst() {
    var asyncService =
        new AsyncIdempotencyService(
            new InMemoryAsyncIdempotencyStore(Clock.systemUTC()),
            Duration.ofSeconds(30),
            Duration.ofHours(1),
            Clock.systemUTC());
    ReactivePaymentsController target = new ReactivePaymentsController();
    ProxyFactory factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAdvice(
        (MethodInterceptor)
            inv ->
                Mono.defer(
                    () -> {
                      if (!allowed.get()) {
                        return Mono.error(new Denied());
                      }
                      try {
                        return (Mono<?>) inv.proceed();
                      } catch (Throwable e) {
                        return Mono.error(e);
                      }
                    }));
    factory.addAdvice(new IdempotencyMethodInterceptor(() -> service, () -> asyncService));
    ReactivePaymentsController reactive = (ReactivePaymentsController) factory.getProxy();
    String fp = IdempotencyService.fingerprint("POST", "/payments", null, new byte[0]);

    ReactiveIdempotency.IdempotencyExchange first =
        new ReactiveIdempotency.IdempotencyExchange("idempotency-key-00000020", fp);
    assertEquals(
        "p-1",
        reactive.pay().contextWrite(c -> c.put(ReactiveIdempotency.CONTEXT_KEY, first)).block());
    IdempotencyService.Proceed execution = first.execution();
    assertTrue(execution != null);
    asyncService
        .complete(execution, new StoredResponse(201, Map.of(), "p-1".getBytes()))
        .toCompletableFuture()
        .join();

    allowed.set(false);
    ReactiveIdempotency.IdempotencyExchange denied =
        new ReactiveIdempotency.IdempotencyExchange("idempotency-key-00000020", fp);
    assertThrows(
        Denied.class,
        () ->
            reactive
                .pay()
                .contextWrite(c -> c.put(ReactiveIdempotency.CONTEXT_KEY, denied))
                .block());

    allowed.set(true);
    ReactiveIdempotency.IdempotencyExchange again =
        new ReactiveIdempotency.IdempotencyExchange("idempotency-key-00000020", fp);
    IdempotentReplay replay =
        assertThrows(
            IdempotentReplay.class,
            () ->
                reactive
                    .pay()
                    .contextWrite(c -> c.put(ReactiveIdempotency.CONTEXT_KEY, again))
                    .block());
    assertEquals(201, replay.response().status());
    assertEquals(1, target.executions.get());
    assertFalse(again.execution() != null);
    assertInstanceOf(IdempotentReplay.class, replay);
  }
}
