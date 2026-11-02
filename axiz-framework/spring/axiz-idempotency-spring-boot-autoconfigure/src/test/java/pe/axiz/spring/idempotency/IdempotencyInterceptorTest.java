package pe.axiz.spring.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.idempotency.IdempotencyService;
import pe.axiz.core.idempotency.Idempotent;
import pe.axiz.core.idempotency.InMemoryIdempotencyStore;
import pe.axiz.core.idempotency.StoredResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/** The method-level interceptor on both stacks, without a web server. */
class IdempotencyInterceptorTest {

  private static final Duration T = Duration.ofSeconds(5);

  /** A controller-like target: annotated, optional-key and plain methods. */
  public static class Payments {
    final AtomicInteger runs = new AtomicInteger();

    @Idempotent
    public String pay() {
      return "paid-" + runs.incrementAndGet();
    }

    @Idempotent(required = false)
    public String note() {
      return "noted-" + runs.incrementAndGet();
    }

    public String plain() {
      return "plain-" + runs.incrementAndGet();
    }

    @Idempotent
    public Mono<String> payReactive() {
      return Mono.fromCallable(() -> "paid-" + runs.incrementAndGet());
    }

    @Idempotent(required = false)
    public Flux<String> statement() {
      return Flux.just("line-" + runs.incrementAndGet());
    }

    @Idempotent
    public Mono<String> nothing() {
      return null;
    }
  }

  private final pe.axiz.core.idempotency.AsyncIdempotencyService asyncService =
      new pe.axiz.core.idempotency.AsyncIdempotencyService(
          new pe.axiz.core.idempotency.InMemoryAsyncIdempotencyStore(Clock.systemUTC()),
          Duration.ofSeconds(30),
          Duration.ofHours(1),
          Clock.systemUTC());
  private final IdempotencyService service =
      new IdempotencyService(
          new InMemoryIdempotencyStore(),
          Duration.ofSeconds(30),
          Duration.ofHours(1),
          Clock.systemUTC());
  private final Payments target = new Payments();
  private final Payments payments = proxy(target, service);

  private Payments proxy(Payments target, IdempotencyService service) {
    ProxyFactory pf = new ProxyFactory(target);
    pf.setProxyTargetClass(true);
    pf.addAdvice(new IdempotencyMethodInterceptor(() -> service, () -> asyncService));
    return (Payments) pf.getProxy();
  }

  @AfterEach
  void clearRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  private static MockHttpServletRequest request(String method, String key) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, "/payments");
    if (key != null) {
      request.addHeader("Idempotency-Key", key);
      request.setAttribute(
          IdempotencySupport.FINGERPRINT_ATTRIBUTE,
          IdempotencyService.fingerprint(
              method, "/payments", "{}".getBytes(StandardCharsets.UTF_8)));
    }
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    return request;
  }

  @Test
  void outsideAWebRequestOrWithoutTheAnnotationTheCallJustRuns() {
    assertEquals("plain-1", payments.plain());
    assertEquals("paid-2", payments.pay(), "not called from a web request");
    request("POST", null);
    assertEquals("plain-3", payments.plain());
  }

  @Test
  void theServletRequestDecidesWhetherTheCallRunsOrIsReplayed() {
    request("GET", null);
    assertEquals("paid-1", payments.pay(), "safe methods are not guarded");

    request("POST", null);
    assertThrows(BusinessException.class, payments::pay, "the key is required");
    assertEquals("noted-2", payments.note(), "an optional key may be missing");

    MockHttpServletRequest first = request("POST", "payment-key-0000001");
    assertEquals("paid-3", payments.pay());
    Object decision = first.getAttribute(IdempotencySupport.KEY_ATTRIBUTE);
    assertInstanceOf(IdempotencyService.Proceed.class, decision);
    assertEquals("paid-4", payments.pay(), "already decided for this request: no new decision");
    service.complete(
        (IdempotencyService.Proceed) decision,
        new StoredResponse(
            201,
            Map.of("Content-Type", "application/json"),
            "{}".getBytes(StandardCharsets.UTF_8)));

    request("POST", "payment-key-0000001");
    IdempotentReplay replay = assertThrows(IdempotentReplay.class, payments::pay);
    assertEquals(201, replay.response().status());
    assertEquals(4, target.runs.get(), "the replay did not run the method");
  }

  private static Context exchange(String key, String fingerprint) {
    return Context.of(
        ReactiveIdempotency.CONTEXT_KEY,
        new ReactiveIdempotency.IdempotencyExchange(key, fingerprint));
  }

  @Test
  void reactiveCallsAreGuardedByTheExchangeOfTheRequest() {
    assertEquals("paid-1", payments.payReactive().block(T), "no exchange: a safe method");

    assertThrows(
        BusinessException.class,
        () -> payments.payReactive().contextWrite(exchange(null, null)).block(T));
    assertEquals(
        List.of("line-2"),
        payments.statement().contextWrite(exchange(null, null)).collectList().block(T),
        "an optional key may be missing, on a Flux too");

    ReactiveIdempotency.IdempotencyExchange first =
        new ReactiveIdempotency.IdempotencyExchange("payment-key-0000002", "fp-1");
    assertEquals(
        "paid-3",
        payments
            .payReactive()
            .contextWrite(Context.of(ReactiveIdempotency.CONTEXT_KEY, first))
            .block(T));
    IdempotencyService.Proceed execution = first.execution();
    assertInstanceOf(IdempotencyService.Proceed.class, execution);
    assertEquals(
        "paid-4",
        payments
            .payReactive()
            .contextWrite(Context.of(ReactiveIdempotency.CONTEXT_KEY, first))
            .block(T),
        "already decided for this exchange");
    asyncService
        .complete(
            execution, new StoredResponse(200, Map.of(), "{}".getBytes(StandardCharsets.UTF_8)))
        .toCompletableFuture()
        .join();

    assertThrows(
        IdempotentReplay.class,
        () ->
            payments.payReactive().contextWrite(exchange("payment-key-0000002", "fp-1")).block(T));
    assertNull(payments.nothing().contextWrite(exchange(null, null)).onErrorComplete().block(T));
    assertNull(
        payments
            .nothing()
            .contextWrite(
                Context.of(
                    ReactiveIdempotency.CONTEXT_KEY,
                    new ReactiveIdempotency.IdempotencyExchange("payment-key-0000003", "fp-3")))
            .block(T),
        "a method that returns no publisher completes empty");
  }
}
