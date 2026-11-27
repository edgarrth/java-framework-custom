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
import java.util.List;
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
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
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

  /** Sustituye a la seguridad de métodos ({@code @PreAuthorize}): se ordena antes del advice. */
  static final class Denied extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  /** Lo que hace el handler simulado una vez que se ejecuta. */
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

  /**
   * El filtro y luego el "dispatcher": el handler con proxy y el manejador de excepciones de
   * reproducción.
   */
  private MockHttpServletResponse call(
      String key,
      String body,
      @Nullable String query,
      @Nullable RequestMetadata caller,
      int maxResponseSize)
      throws Exception {
    IdempotencyFilter filter = new IdempotencyFilter(service, 1024, maxResponseSize, List.of());
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
    // de 16 a 128 caracteres de [A-Za-z0-9._:-]: demasiado corta, demasiado larga y caracteres
    // inseguros
    for (String key : new String[] {"key-00000001", "k".repeat(129), "key with spaces 0001"}) {
      BusinessException e = assertThrows(BusinessException.class, () -> call(key, "{}"));
      assertEquals(400, e.category().httpStatus(), key);
    }
    assertEquals(0, controller.executions.get());
  }

  /**
   * La primera solicitud se permitió y luego se revocó el permiso del invocante; repetirla con la
   * misma clave debe denegarse sin devolver el cuerpo almacenado.
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

  /** Una excepción que escapa del handler no se almacena como un 200. */
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
   * Un error del servidor (un fallo técnico transitorio: timeout de la base de datos, servicio
   * aguas abajo no disponible) nunca se almacena: la clave se libera y un reintento con la misma
   * clave se ejecuta de nuevo.
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
   * Un error del cliente es una decisión sobre esa solicitud exacta: se almacena y se reproduce, de
   * modo que la misma clave con el mismo cuerpo obtiene la misma respuesta y la operación nunca se
   * ejecuta dos veces.
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

  /** La query string forma parte de la huella, tomada tal como se envió. */
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

  /** Dos invocantes que comparten una clave nunca ven las respuestas del otro. */
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

  /** Las respuestas grandes se transmiten completas, pero nunca se almacenan enteras en búfer. */
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
    assertNull(capturing.capture().body());
    assertEquals(0, capturing.capture().buffered(), "memory does not grow with the response");
  }

  @Test
  void boundedCaptureKeepsSmallBodies() {
    BoundedCapture capture = new BoundedCapture(4);
    capture.write(new byte[] {1, 2});
    capture.write(new byte[] {3, 4});
    assertArrayEquals(new byte[] {1, 2, 3, 4}, capture.body());
    capture.write(new byte[] {5});
    assertNull(capture.body());
    assertEquals(
        Map.of("Location", "/x", StoredResponse.BODY_OMITTED_HEADER, "true"),
        IdempotencySupport.stored(201, Map.of("Location", "/x"), new byte[8], 4).headers());
  }

  /** En WebFlux, la decisión se toma dentro de la seguridad de métodos reactiva. */
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
