package pe.axiz.spring.idempotency;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.crypto.AsyncDataKeyProvider;
import pe.axiz.core.crypto.AsyncEnvelopeEncryptor;
import pe.axiz.core.crypto.AsyncEnvelopePayloadProtector;
import pe.axiz.core.crypto.AsyncPayloadProtector;
import pe.axiz.core.crypto.CryptoException;
import pe.axiz.core.crypto.DataKeyProvider;
import pe.axiz.core.crypto.DataKeyProvider.DataKey;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import pe.axiz.core.crypto.EnvelopePayloadProtector;
import pe.axiz.core.crypto.PayloadProtector;
import pe.axiz.core.idempotency.AsyncIdempotencyService;
import pe.axiz.core.idempotency.IdempotencyService;
import pe.axiz.core.idempotency.Idempotent;
import pe.axiz.core.idempotency.InMemoryAsyncIdempotencyStore;
import pe.axiz.core.idempotency.InMemoryIdempotencyStore;
import pe.axiz.core.idempotency.StoredResponse;
import pe.axiz.core.idempotency.StoredResponseCodec;
import reactor.core.publisher.Mono;

class IdempotencyEncryptionTest {
  private static final byte[] BODY = "{\"document\":\"00123456\"}".getBytes(StandardCharsets.UTF_8);
  private static final String KEY = "idempotency-key-00000001";
  private static final DataKeyProvider KEYS =
      new DataKeyProvider() {
        public DataKey generate(String context) {
          return new DataKey(new byte[32], new byte[] {1});
        }

        public byte[] decrypt(byte[] key, String context) {
          return new byte[32];
        }
      };

  private static RequestMetadata caller(String tenant) {
    return RequestMetadata.builder("correlation", "request")
        .tenantId(tenant)
        .subject("user")
        .clientId("client")
        .issuer("issuer")
        .build();
  }

  @Test
  void authenticatedReplayRejectsTamperedStatusAndHeaders() {
    try (var envelope =
        new EnvelopeEncryptor(KEYS, Duration.ofMinutes(1), 100, Clock.systemUTC())) {
      var protection = new EnvelopePayloadProtector(envelope);
      var binding = IdempotencyPayloads.Binding.of("create", "tenant-a", KEY);
      var headers = Map.of("Content-Type", "application/json", "Location", "/resources/1");
      var stored = IdempotencyPayloads.store(201, headers, BODY.clone(), binding, protection);
      assertArrayEquals(BODY, IdempotencyPayloads.reveal(stored, binding, protection).body());
      var omitted = StoredResponse.withoutBody(201, headers);
      var authenticatedEmpty =
          IdempotencyPayloads.store(201, omitted.headers(), omitted.body(), binding, protection);
      assertTrue(IdempotencyPayloads.reveal(authenticatedEmpty, binding, protection).bodyOmitted());
      assertEquals(
          0, IdempotencyPayloads.reveal(authenticatedEmpty, binding, protection).body().length);
      assertThrows(
          CryptoException.class, () -> IdempotencyPayloads.reveal(omitted, binding, protection));
      assertThrows(
          CryptoException.class,
          () ->
              IdempotencyPayloads.reveal(
                  new StoredResponse(200, headers, stored.body()), binding, protection));
      assertThrows(
          CryptoException.class,
          () ->
              IdempotencyPayloads.reveal(
                  new StoredResponse(201, Map.of("Location", "/resources/other"), stored.body()),
                  binding,
                  protection));
    }
  }

  private static IdempotencyTest.PaymentsController proxy(
      IdempotencyTest.PaymentsController api,
      IdempotencyService service,
      PayloadProtector protection) {
    var factory = new ProxyFactory(api);
    factory.setProxyTargetClass(true);
    factory.addAdvice(
        new IdempotencyMethodInterceptor(
            () -> service,
            () -> {
              throw new AssertionError("Imperative test");
            },
            () -> protection,
            () -> AsyncPayloadProtector.NONE));
    return (IdempotencyTest.PaymentsController) factory.getProxy();
  }

  private static MockHttpServletResponse call(
      IdempotencyTest.PaymentsController proxy,
      IdempotencyService service,
      PayloadProtector protection,
      RequestMetadata caller,
      String key)
      throws Exception {
    var request = new MockHttpServletRequest("POST", "/payments");
    request.addHeader("Idempotency-Key", key);
    var response = new MockHttpServletResponse();
    new IdempotencyFilter(service, 1024, 1024, List.of(), protection)
        .doFilter(
            request,
            response,
            (req, res) -> {
              RequestContextHolder.setRequestAttributes(
                  new ServletRequestAttributes(
                      (HttpServletRequest) req, (HttpServletResponse) res));
              try (var ignored = RequestContext.open(caller)) {
                proxy.pay((HttpServletResponse) res);
              } catch (IdempotentReplay replay) {
                var entity = new IdempotencyReplayHandler().replay(replay);
                ((HttpServletResponse) res).setStatus(entity.getStatusCode().value());
                entity
                    .getHeaders()
                    .forEach(
                        (name, values) ->
                            ((HttpServletResponse) res).setHeader(name, values.getFirst()));
                ((HttpServletResponse) res).getOutputStream().write(entity.getBody());
              } catch (RuntimeException failure) {
                throw failure;
              } catch (Exception failure) {
                throw new IllegalStateException(failure);
              } finally {
                RequestContextHolder.resetRequestAttributes();
              }
            });
    return response;
  }

  @Test
  void servletReplayRestoresExactBytesAndRejectsCiphertextCopiedAcrossTenants() throws Exception {
    var store = new InMemoryIdempotencyStore();
    var service =
        new IdempotencyService(
            store, Duration.ofSeconds(30), Duration.ofHours(1), Clock.systemUTC());
    var api = new IdempotencyTest.PaymentsController();
    api.handler =
        (response, count) -> {
          response.setStatus(201);
          response.getOutputStream().write(BODY);
        };
    try (var envelope =
        new EnvelopeEncryptor(KEYS, Duration.ofMinutes(1), 100, Clock.systemUTC())) {
      var protection = new EnvelopePayloadProtector(envelope);
      var proxy = proxy(api, service, protection);
      var first = call(proxy, service, protection, caller("tenant-a"), KEY);
      var replay = call(proxy, service, protection, caller("tenant-a"), KEY);
      assertArrayEquals(BODY, first.getContentAsByteArray());
      assertArrayEquals(BODY, replay.getContentAsByteArray());
      assertEquals(1, api.executions.get());
      String operation = IdempotencyTest.PaymentsController.class.getName() + "#pay";
      String scope = IdempotencySupport.scope(operation, caller("tenant-a"));
      var record = store.find(scope + ":" + KEY, Instant.now()).orElseThrow();
      assertFalse(StoredResponseCodec.write(record).contains("00123456"));
      assertArrayEquals(
          new byte[] {'A', 'X', 'Z', 'P'}, Arrays.copyOf(record.response().body(), 4));
      var copied =
          (IdempotencyService.Proceed)
              service.begin(
                  IdempotencySupport.scope(operation, caller("tenant-b")),
                  KEY,
                  IdempotencyService.fingerprint("POST", "/payments", null, new byte[0]));
      assertTrue(service.complete(copied, record.response()));
      assertThrows(
          CryptoException.class, () -> call(proxy, service, protection, caller("tenant-b"), KEY));
      assertThrows(
          CryptoException.class,
          () ->
              call(
                  proxy(api, service, PayloadProtector.NONE),
                  service,
                  PayloadProtector.NONE,
                  caller("tenant-a"),
                  KEY));
      assertEquals(1, api.executions.get());
    }
  }

  @RestController
  public static class NativeApi {
    final AtomicInteger calls = new AtomicInteger();

    @Idempotent
    @PostMapping("/payments")
    public Mono<ResponseEntity<byte[]>> pay() {
      return Mono.fromSupplier(
          () -> {
            calls.incrementAndGet();
            return ResponseEntity.status(201).body(BODY);
          });
    }
  }

  @Test
  void nativeReplayUsesNativeEncryptionAndStableStoredBytesForAtomicComparison() {
    var keys =
        new AsyncDataKeyProvider() {
          public CompletionStage<DataKey> generate(String context) {
            return CompletableFuture.completedFuture(KEYS.generate(context));
          }

          public CompletionStage<byte[]> decrypt(byte[] key, String context) {
            return CompletableFuture.completedFuture(KEYS.decrypt(key, context));
          }
        };
    var store = new InMemoryAsyncIdempotencyStore(Clock.systemUTC());
    var service =
        new AsyncIdempotencyService(
            store, Duration.ofSeconds(30), Duration.ofHours(1), Clock.systemUTC());
    var api = new NativeApi();
    try (var envelope =
        new AsyncEnvelopeEncryptor(keys, Duration.ofMinutes(1), 100, Clock.systemUTC())) {
      var protection = new AsyncEnvelopePayloadProtector(envelope);
      var factory = new ProxyFactory(api);
      factory.setProxyTargetClass(true);
      factory.addAdvice(
          new IdempotencyMethodInterceptor(
              () -> {
                throw new AssertionError("No blocking fallback");
              },
              () -> service,
              () -> {
                throw new AssertionError("No blocking cryptography");
              },
              () -> protection));
      var client =
          WebTestClient.bindToController(factory.getProxy())
              .controllerAdvice(
                  new IdempotencyReplayHandler(), new ReactiveIdempotencyFilterTest.Errors())
              .webFilter(new IdempotencyWebFilter(service, 1024, 1024, List.of(), protection))
              .build();
      client
          .post()
          .uri("/payments")
          .header("Idempotency-Key", KEY)
          .exchange()
          .expectStatus()
          .isCreated()
          .expectBody(byte[].class)
          .isEqualTo(BODY);
      String scope = IdempotencySupport.scope(NativeApi.class.getName() + "#pay", null);
      Awaitility.await()
          .atMost(Duration.ofSeconds(3))
          .until(
              () ->
                  store
                          .find(scope + ":" + KEY, Instant.now())
                          .toCompletableFuture()
                          .join()
                          .orElseThrow()
                          .response()
                      != null);
      var record =
          store.find(scope + ":" + KEY, Instant.now()).toCompletableFuture().join().orElseThrow();
      String encoded = StoredResponseCodec.write(record);
      assertFalse(encoded.contains("00123456"));
      assertTrue(
          store
              .compareAndSet(StoredResponseCodec.read(record.key(), encoded), record)
              .toCompletableFuture()
              .join());
      client
          .post()
          .uri("/payments")
          .header("Idempotency-Key", KEY)
          .exchange()
          .expectStatus()
          .isCreated()
          .expectHeader()
          .valueEquals(IdempotencyReplayHandler.REPLAYED_HEADER, "true")
          .expectBody(byte[].class)
          .isEqualTo(BODY);
      assertEquals(1, api.calls.get());
    }
  }
}
