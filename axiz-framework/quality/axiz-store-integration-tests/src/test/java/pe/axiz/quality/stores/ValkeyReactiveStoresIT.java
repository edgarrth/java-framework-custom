package pe.axiz.quality.stores;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.GenericContainer;
import pe.axiz.adapter.cache.redis.RedisAsyncByteCache;
import pe.axiz.adapter.idempotency.redis.RedisAsyncIdempotencyStore;
import pe.axiz.adapter.lock.redis.RedisAsyncDistributedLock;
import pe.axiz.adapter.security.redis.RedisAsyncNonceStore;
import pe.axiz.adapter.security.redis.RedisAsyncRateLimiter;
import pe.axiz.core.idempotency.IdempotencyRecord;
import pe.axiz.core.idempotency.StoredResponse;
import pe.axiz.core.security.RateLimitUnavailableException;
import pe.axiz.quality.containers.TestContainerImages;
import pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * Real Lua/TTL behavior and reconnect/timeout/cancellation on nonblocking callers and callbacks.
 */
@ExtendWith(BlockHoundExtension.class)
class ValkeyReactiveStoresIT {
  private static final Duration T = Duration.ofSeconds(10);

  @SuppressWarnings("resource")
  private static final GenericContainer<?> VALKEY =
      TestContainerImages.valkey().withExposedPorts(6379);

  private static RedisClient client;
  private static StatefulRedisConnection<String, String> strings;
  private static StatefulRedisConnection<String, byte[]> bytes;
  private static RedisAsyncByteCache cache;

  @BeforeAll
  static void start() throws Exception {
    Docker.require();
    ReactiveInfrastructureBootstrap.initialize();
    // Keep the endpoint stable across Docker restart: auto-assigned host ports may change.
    // Owned local Valkey container without TLS; this fixture tests store contracts and
    // cancellation.
    // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
    try (var port = new ServerSocket(0)) {
      VALKEY.setPortBindings(List.of(port.getLocalPort() + ":6379"));
    }
    VALKEY.start();
    client = RedisClient.create("redis://127.0.0.1:" + VALKEY.getMappedPort(6379));
    client.setOptions(
        ClientOptions.builder()
            .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(500)))
            .build());
    strings = client.connect();
    bytes = client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
    cache = new RedisAsyncByteCache(bytes.async());
  }

  @AfterAll
  static void close() {
    try {
      if (strings != null) strings.close();
      if (bytes != null) bytes.close();
      if (client != null) client.shutdown();
    } finally {
      VALKEY.stop();
    }
    assertTrue(
        BlockHoundExtension.detectedBlockingCalls().isEmpty(),
        () -> BlockHoundExtension.detectedBlockingCalls().toString());
  }

  private static <T> Mono<T> call(Supplier<CompletionStage<T>> work) {
    return Mono.fromCompletionStage(work).subscribeOn(Schedulers.parallel());
  }

  @Test
  void atomicCacheTtlAndLiteralPrefixEvictionRunFromReactiveThreads() {
    StepVerifier.create(
            call(() -> cache.put("literal[*]:one", new byte[] {1, 2}, Duration.ofSeconds(30)))
                .then(call(() -> cache.getWithTtl("literal[*]:one"))))
        .assertNext(
            found -> {
              var value = found.orElseThrow();
              assertArrayEquals(new byte[] {1, 2}, value.value());
              assertTrue(value.remaining().orElseThrow().compareTo(Duration.ofSeconds(30)) <= 0);
              assertTrue(value.remaining().orElseThrow().isPositive());
            })
        .expectComplete()
        .verify(T);
    StepVerifier.create(
            call(() -> cache.put("literalX:keep", new byte[] {3}, Duration.ofSeconds(30)))
                .then(call(() -> cache.evictByPrefix("literal[*]:"))))
        .expectNext(1L)
        .expectComplete()
        .verify(T);
    StepVerifier.create(call(() -> cache.get("literalX:keep")))
        .assertNext(value -> assertTrue(value.isPresent()))
        .expectComplete()
        .verify(T);
  }

  @Test
  void leasesRateLimitsAndNoncesKeepAtomicityAcrossConnections() {
    var locks = new RedisAsyncDistributedLock(strings.async(), "reactive:lock:");
    StepVerifier.create(
            call(() -> locks.tryAcquire("account", Duration.ofSeconds(30)))
                .flatMap(
                    found -> {
                      var lease = found.orElseThrow();
                      return call(() -> lease.renew(Duration.ofSeconds(30)))
                          .doOnNext(renewed -> assertTrue(renewed))
                          .then(call(() -> locks.tryAcquire("account", Duration.ofSeconds(30))))
                          .doOnNext(competing -> assertTrue(competing.isEmpty()))
                          .then(call(lease::release))
                          .then(call(() -> locks.tryAcquire("account", Duration.ofSeconds(30))))
                          .flatMap(next -> call(next.orElseThrow()::release));
                    }))
        .expectComplete()
        .verify(T);
    var limits = new RedisAsyncRateLimiter(strings.async(), "reactive:rate:");
    StepVerifier.create(
            call(() -> limits.tryAcquire("account", 1, Duration.ofSeconds(30), false))
                .doOnNext(result -> assertTrue(result.allowed()))
                .then(call(() -> limits.tryAcquire("account", 1, Duration.ofSeconds(30), false))))
        .assertNext(
            result -> {
              assertFalse(result.allowed());
              assertTrue(result.retryAfter().isPositive());
            })
        .expectComplete()
        .verify(T);
    var nonces = new RedisAsyncNonceStore(strings.async(), "reactive:nonce:");
    StepVerifier.create(
            call(() -> nonces.markUsed("once", Duration.ofSeconds(30)))
                .doOnNext(first -> assertTrue(first))
                .then(call(() -> nonces.markUsed("once", Duration.ofSeconds(30)))))
        .expectNext(false)
        .expectComplete()
        .verify(T);
  }

  @Test
  void asyncIdempotencyRetainsTheFullResponseAcrossARecreatedConnection() {
    var store =
        new RedisAsyncIdempotencyStore(strings.async(), "reactive:idem:", Clock.systemUTC());
    var now = Instant.now();
    var record =
        IdempotencyRecord.builder("payment", "hash", now.plusSeconds(30), now.plusSeconds(30))
            .status(IdempotencyRecord.Status.IN_PROGRESS)
            .owner("owner")
            .response(null)
            .resolutionVersion(0)
            .failure(null)
            .build();
    var response = new StoredResponse(201, Map.of("Location", "/payments/1"), new byte[] {0, 127});
    StepVerifier.create(
            call(() -> store.tryStart(record))
                .doOnNext(started -> assertTrue(started))
                .then(
                    call(() -> store.complete("payment", "owner", response, now.plusSeconds(30)))))
        .expectNext(true)
        .expectComplete()
        .verify(T);
    StepVerifier.create(
            call(() ->
                    client.connectAsync(
                        StringCodec.UTF8,
                        RedisURI.create("redis://127.0.0.1:" + VALKEY.getMappedPort(6379))))
                .flatMap(
                    connection -> {
                      var reopened =
                          new RedisAsyncIdempotencyStore(
                              connection.async(), "reactive:idem:", Clock.systemUTC());
                      return call(() -> reopened.find("payment", now))
                          .doOnNext(found -> assertEquals(response, found.orElseThrow().response()))
                          .then(call(connection::closeAsync));
                    }))
        .expectComplete()
        .verify(T);
  }

  @Test
  void stalledServerTimesOutAndCancellationDoesNotBlockItsCaller() throws Exception {
    VALKEY.getDockerClient().pauseContainerCmd(VALKEY.getContainerId()).exec();
    try {
      var locks = new RedisAsyncDistributedLock(strings.async(), "cancel:lock:");
      var nonces = new RedisAsyncNonceStore(strings.async(), "cancel:nonce:");
      var idem = new RedisAsyncIdempotencyStore(strings.async(), "cancel:idem:", Clock.systemUTC());
      var limits = new RedisAsyncRateLimiter(strings.async(), "cancel:rate:");
      verifyCancellation(() -> cache.get("cancelled"));
      verifyCancellation(() -> locks.tryAcquire("cancelled", Duration.ofSeconds(1)));
      verifyCancellation(() -> nonces.markUsed("cancelled", Duration.ofSeconds(1)));
      verifyCancellation(() -> idem.find("cancelled", Instant.now()));
      verifyCancellation(() -> limits.tryAcquire("cancelled", 1, Duration.ofSeconds(1), false));
      StepVerifier.create(call(() -> cache.get("timeout")))
          .expectError(RedisCommandTimeoutException.class)
          .verify(T);
      StepVerifier.create(call(() -> locks.tryAcquire("timeout", Duration.ofSeconds(1))))
          .expectError(RedisCommandTimeoutException.class)
          .verify(T);
      StepVerifier.create(call(() -> nonces.markUsed("timeout", Duration.ofSeconds(1))))
          .expectError(RedisCommandTimeoutException.class)
          .verify(T);
      StepVerifier.create(call(() -> idem.find("timeout", Instant.now())))
          .expectError(RedisCommandTimeoutException.class)
          .verify(T);
      StepVerifier.create(
              call(() -> limits.tryAcquire("timeout-closed", 1, Duration.ofSeconds(1), false)))
          .expectError(RateLimitUnavailableException.class)
          .verify(T);
      StepVerifier.create(
              call(() -> limits.tryAcquire("timeout-open", 1, Duration.ofSeconds(1), true)))
          .assertNext(result -> assertTrue(result.allowed()))
          .expectComplete()
          .verify(T);
    } finally {
      VALKEY.getDockerClient().unpauseContainerCmd(VALKEY.getContainerId()).exec();
    }
    StepVerifier.create(call(() -> cache.put("alive", new byte[] {1}, Duration.ofSeconds(30))))
        .expectComplete()
        .verify(T);
  }

  private static <T> void verifyCancellation(Supplier<CompletionStage<T>> work) throws Exception {
    var submitted = new CountDownLatch(1);
    var source = new AtomicReference<CompletableFuture<?>>();
    var result =
        call(() -> {
              var stage = work.get().toCompletableFuture();
              source.set(stage);
              submitted.countDown();
              return stage;
            })
            .toFuture();
    assertTrue(submitted.await(3, TimeUnit.SECONDS));
    result.cancel(false);
    Awaitility.await().atMost(T).until(() -> source.get().isCancelled());
  }

  @Test
  void reconnectsAfterServerRestartWithoutOffloadingStoreCalls() {
    VALKEY.getDockerClient().restartContainerCmd(VALKEY.getContainerId()).exec();
    Awaitility.await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () ->
                StepVerifier.create(
                        call(() -> strings.async().ping())
                            .doOnNext(reply -> assertEquals("PONG", reply))
                            .then(
                                call(
                                    () ->
                                        cache.put(
                                            "reconnected", new byte[] {4}, Duration.ofSeconds(30))))
                            .then(call(() -> cache.get("reconnected"))))
                    .assertNext(found -> assertArrayEquals(new byte[] {4}, found.orElseThrow()))
                    .expectComplete()
                    .verify(T));
  }
}
