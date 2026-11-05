package pe.axiz.spring.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import pe.axiz.core.idempotency.AsyncIdempotencyService;
import pe.axiz.core.idempotency.Idempotent;
import pe.axiz.core.idempotency.InMemoryAsyncIdempotencyStore;
import pe.axiz.core.idempotency.StoredResponse;
import reactor.core.publisher.Mono;

/** The reactive filter end to end: first execution stored, retries replayed, failures released. */
class ReactiveIdempotencyFilterTest {

  @RestController
  public static class Api {
    final AtomicInteger executions = new AtomicInteger();
    volatile HttpStatus status = HttpStatus.CREATED;
    volatile int size = 0;

    @Idempotent
    @PostMapping("/payments")
    public Mono<ResponseEntity<String>> pay(@RequestBody(required = false) String body) {
      return Mono.fromSupplier(
          () -> {
            int n = executions.incrementAndGet();
            String payload = size > 0 ? "x".repeat(size) : "{\"id\":\"p-" + n + "\"}";
            return ResponseEntity.status(status)
                .header("Location", "/payments/p-" + n)
                .body(payload);
          });
    }

    @GetMapping("/payments")
    public Mono<String> list() {
      executions.incrementAndGet();
      return Mono.just("[]");
    }
  }

  @org.springframework.web.bind.annotation.RestControllerAdvice
  static class Errors {
    @org.springframework.web.bind.annotation.ExceptionHandler(
        pe.axiz.core.errors.BusinessException.class)
    ResponseEntity<Void> problem(pe.axiz.core.errors.BusinessException failure) {
      return ResponseEntity.status(failure.category().httpStatus()).build();
    }
  }

  private final AsyncIdempotencyService service =
      new AsyncIdempotencyService(
          new InMemoryAsyncIdempotencyStore(Clock.systemUTC()),
          Duration.ofSeconds(30),
          Duration.ofHours(1),
          Clock.systemUTC());
  private final Api api = new Api();

  private WebTestClient client(int maxResponseSize) {
    ProxyFactory factory = new ProxyFactory(api);
    factory.setProxyTargetClass(true);
    factory.addAdvice(
        new IdempotencyMethodInterceptor(
            () -> {
              throw new AssertionError("reactive code must not use the synchronous service");
            },
            () -> service));
    return WebTestClient.bindToController(factory.getProxy())
        .controllerAdvice(new IdempotencyReplayHandler(), new Errors())
        .webFilter(new IdempotencyWebFilter(service, 1024, maxResponseSize))
        .build();
  }

  private WebTestClient.ResponseSpec post(WebTestClient client, String key, String body) {
    return client.post().uri("/payments").header("Idempotency-Key", key).bodyValue(body).exchange();
  }

  /**
   * The record is settled on a worker once the response is out: until then a retry sees the key in
   * progress (409). Retries like a client would, for at most five seconds, and returns the first
   * answer that is not 409.
   */
  private EntityExchangeResult<String> retry(WebTestClient client, String key, String body)
      throws InterruptedException {
    return org.awaitility.Awaitility.await()
        .pollDelay(Duration.ZERO)
        .pollInterval(Duration.ofMillis(25))
        .atMost(Duration.ofSeconds(5))
        .until(
            () -> post(client, key, body).expectBody(String.class).returnResult(),
            result -> result.getStatus().value() != 409);
  }

  @Test
  void aRetryWithTheSameKeyIsReplayedFromTheStore() throws Exception {
    WebTestClient client = client(Integer.MAX_VALUE);
    post(client, "idempotency-key-00000100", "{\"amount\":10}")
        .expectStatus()
        .isCreated()
        .expectBody(String.class)
        .isEqualTo("{\"id\":\"p-1\"}");
    EntityExchangeResult<String> replay =
        retry(client, "idempotency-key-00000100", "{\"amount\":10}");
    assertEquals(201, replay.getStatus().value());
    assertEquals(
        "true", replay.getResponseHeaders().getFirst(IdempotencyReplayHandler.REPLAYED_HEADER));
    assertEquals("/payments/p-1", replay.getResponseHeaders().getFirst("Location"));
    assertEquals("{\"id\":\"p-1\"}", replay.getResponseBody());
    assertEquals(1, api.executions.get());
  }

  @Test
  void aServerErrorPreservesTheUncertainOperation() throws Exception {
    WebTestClient client = client(Integer.MAX_VALUE);
    api.status = HttpStatus.SERVICE_UNAVAILABLE;
    post(client, "idempotency-key-00000101", "{}").expectStatus().isEqualTo(503);
    api.status = HttpStatus.CREATED;
    post(client, "idempotency-key-00000101", "{}").expectStatus().isEqualTo(409);
    assertEquals(1, api.executions.get(), "an uncertain 503 must not authorize another effect");
  }

  @Test
  void aLargeResponseStreamsToTheClientAndIsReplayedWithoutBody() throws Exception {
    WebTestClient client = client(16);
    api.size = 64;
    post(client, "idempotency-key-00000102", "{}")
        .expectStatus()
        .isCreated()
        .expectBody(String.class)
        .isEqualTo("x".repeat(64));
    EntityExchangeResult<String> replay = retry(client, "idempotency-key-00000102", "{}");
    assertEquals(201, replay.getStatus().value());
    assertEquals("true", replay.getResponseHeaders().getFirst(StoredResponse.BODY_OMITTED_HEADER));
    assertEquals(
        "true", replay.getResponseHeaders().getFirst(IdempotencyReplayHandler.REPLAYED_HEADER));
    assertEquals(1, api.executions.get(), "the operation never runs twice");
  }

  @Test
  void safeMethodsPassAndAMissingRequiredKeyIsRejected() {
    WebTestClient client = client(Integer.MAX_VALUE);
    client.get().uri("/payments").exchange().expectStatus().isOk();
    client
        .post()
        .uri("/payments")
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .value(status -> assertThat(status).isGreaterThanOrEqualTo(400));
    assertEquals(
        1, api.executions.get(), "a required key that is missing never runs the operation");
    assertThat(new IdempotencyWebFilter(service, 1, 1).getOrder())
        .isEqualTo(IdempotencyWebFilter.ORDER);
  }
}
