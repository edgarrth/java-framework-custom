package pe.axiz.quality.stores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.awaitility.Awaitility;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import pe.axiz.core.crypto.AsyncDataKeyProvider;
import pe.axiz.core.crypto.AsyncEnvelopeEncryptor;
import pe.axiz.core.crypto.AsyncEnvelopePayloadProtector;
import pe.axiz.core.crypto.AsyncPayloadProtector;
import pe.axiz.core.crypto.CryptoException;
import pe.axiz.core.crypto.DataKeyProvider.DataKey;
import pe.axiz.core.idempotency.AsyncIdempotencyService;
import pe.axiz.core.idempotency.AsyncIdempotencyStore;
import pe.axiz.core.idempotency.IdempotencyRecord;
import pe.axiz.core.idempotency.IdempotencyService;
import pe.axiz.core.idempotency.IdempotencyStore;
import pe.axiz.core.idempotency.Idempotent;
import pe.axiz.core.idempotency.StoredResponseCodec;
import pe.axiz.spring.idempotency.IdempotencyMethodInterceptor;
import pe.axiz.spring.idempotency.IdempotencyReplayHandler;
import pe.axiz.spring.idempotency.IdempotencyWebFilter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/** Real-store checks share the public HTTP idempotency path; only key wrapping is synthetic. */
final class ProtectedReplayChecks {
  private static final byte[] BODY = "{\"document\":\"00123456\"}".getBytes(StandardCharsets.UTF_8);

  private ProtectedReplayChecks() {}

  @RestController
  public static class Api {
    final AtomicInteger effects = new AtomicInteger();

    @Idempotent
    @PostMapping("/protected-replay")
    public Mono<ResponseEntity<byte[]>> pay() {
      return Mono.fromSupplier(
          () -> {
            effects.incrementAndGet();
            return ResponseEntity.status(201).body(BODY.clone());
          });
    }
  }

  @RestControllerAdvice
  static class Errors {
    @ExceptionHandler(CryptoException.class)
    ResponseEntity<Void> corrupted() {
      return ResponseEntity.internalServerError().build();
    }
  }

  private static WebTestClient client(
      Api api, AsyncIdempotencyService service, AsyncPayloadProtector protector) {
    var proxy = new ProxyFactory(api);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new IdempotencyMethodInterceptor(
            () -> {
                  throw new AssertionError("No blocking service fallback");
                },
                () -> service,
            () -> {
                  throw new AssertionError("No blocking crypto fallback");
                },
                () -> protector));
    return WebTestClient.bindToController(proxy.getProxy())
        .controllerAdvice(new IdempotencyReplayHandler(), new Errors())
        .webFilter(new IdempotencyWebFilter(service, 1024, 1024, List.of(), protector))
        .configureClient()
        .responseTimeout(Duration.ofSeconds(10))
        .build();
  }

  static void verify(
      IdempotencyStore imperative,
      AsyncIdempotencyStore nativeStore,
      Function<String, String> persisted) {
    var keys =
        new AsyncDataKeyProvider() {
          public CompletionStage<DataKey> generate(String context) {
            return CompletableFuture.completedFuture(new DataKey(new byte[32], new byte[] {1}));
          }

          public CompletionStage<byte[]> decrypt(byte[] encrypted, String context) {
            return CompletableFuture.completedFuture(new byte[32]);
          }
        };
    var api = new Api();
    var service =
        new AsyncIdempotencyService(
            nativeStore, Duration.ofSeconds(30), Duration.ofHours(1), Clock.systemUTC());
    String key = UUID.randomUUID().toString();
    String scope = IdempotencyService.scope(Api.class.getName() + "#pay", null, null, null, null);
    String scoped = scope + ":" + key;
    try (var envelope =
        new AsyncEnvelopeEncryptor(keys, Duration.ofMinutes(1), 1000, Clock.systemUTC())) {
      var protector = new AsyncEnvelopePayloadProtector(envelope);
      var http = client(api, service, protector);
      http.post()
          .uri("/protected-replay")
          .header("Idempotency-Key", key)
          .exchange()
          .expectStatus()
          .isCreated()
          .expectBody(byte[].class)
          .isEqualTo(BODY);
      Awaitility.await()
          .atMost(Duration.ofSeconds(10))
          .until(
              () ->
                  imperative
                      .find(scoped, Instant.now())
                      .filter(row -> row.status() == IdempotencyRecord.Status.COMPLETED)
                      .isPresent());
      var row = imperative.find(scoped, Instant.now()).orElseThrow();
      assertThat(persisted.apply(scoped)).doesNotContain("00123456");
      assertThat(Arrays.copyOf(row.response().body(), 4))
          .containsExactly((byte) 'A', (byte) 'X', (byte) 'Z', (byte) 'P');
      assertEquals(row, StoredResponseCodec.read(scoped, StoredResponseCodec.write(row)));
      http.post()
          .uri("/protected-replay")
          .header("Idempotency-Key", key)
          .exchange()
          .expectStatus()
          .isCreated()
          .expectHeader()
          .valueEquals(IdempotencyReplayHandler.REPLAYED_HEADER, "true")
          .expectBody(byte[].class)
          .isEqualTo(BODY);
      assertEquals(1, api.effects.get());
      // All contenders compare the exact persisted ciphertext; encryption is never repeated by CAS.
      StepVerifier.create(
              Flux.range(0, 32)
                  .flatMap(
                      ignored ->
                          Mono.fromCompletionStage(
                              () ->
                                  nativeStore.compareAndSet(
                                      row, row.completed(row.response(), row.retainUntil()))),
                      16)
                  .filter(Boolean::booleanValue)
                  .count()
                  .subscribeOn(Schedulers.parallel()))
          .expectNext(1L)
          .expectComplete()
          .verify(Duration.ofSeconds(15));
      var current = imperative.find(scoped, Instant.now()).orElseThrow();
      assertEquals(row.response(), current.response());
      assertEquals(row.resolutionVersion() + 1, current.resolutionVersion());
      assertTrue(
          imperative.compareAndSet(
              current, current.completed(current.response(), current.retainUntil())));
      http.post()
          .uri("/protected-replay")
          .header("Idempotency-Key", key)
          .exchange()
          .expectStatus()
          .isCreated()
          .expectBody(byte[].class)
          .isEqualTo(BODY);
      // Replaying the same bytes under another request key must fail authentication.
      String copiedKey = UUID.randomUUID().toString();
      var copied =
          IdempotencyRecord.builder(
                  scope + ":" + copiedKey,
                  row.fingerprint(),
                  Instant.now().plusSeconds(60),
                  Instant.now().plusSeconds(60))
              .status(IdempotencyRecord.Status.IN_PROGRESS)
              .owner("copy-owner")
              .response(null)
              .resolutionVersion(0)
              .failure(null)
              .build();
      assertTrue(imperative.tryStart(copied));
      assertTrue(
          imperative.complete(copied.key(), "copy-owner", row.response(), row.retainUntil()));
      http.post()
          .uri("/protected-replay")
          .header("Idempotency-Key", copiedKey)
          .exchange()
          .expectStatus()
          .is5xxServerError()
          .expectBody()
          .isEmpty();
      client(api, service, AsyncPayloadProtector.NONE)
          .post()
          .uri("/protected-replay")
          .header("Idempotency-Key", key)
          .exchange()
          .expectStatus()
          .is5xxServerError()
          .expectBody()
          .isEmpty();
      assertEquals(1, api.effects.get());
    }
  }
}
