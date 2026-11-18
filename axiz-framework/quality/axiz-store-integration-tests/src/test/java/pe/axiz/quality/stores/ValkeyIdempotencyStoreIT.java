package pe.axiz.quality.stores;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.testcontainers.containers.GenericContainer;
import pe.axiz.adapter.idempotency.redis.RedisAsyncIdempotencyStore;
import pe.axiz.adapter.idempotency.redis.RedisIdempotencyStore;
import pe.axiz.core.idempotency.IdempotencyRecord;
import pe.axiz.core.idempotency.IdempotencyRecord.Status;
import pe.axiz.core.idempotency.IdempotencyStore;
import pe.axiz.core.idempotency.StoredResponse;
import pe.axiz.quality.containers.TestContainerImages;
import pe.axiz.test.contracts.IdempotencyStoreContract;
import pe.axiz.test.support.MutableClock;

/** Idempotency store contract on a real Valkey (ElastiCache): Lua scripts and server TTL. */
class ValkeyIdempotencyStoreIT {

  private static final String PREFIX = "axiz:it:idem:";

  @SuppressWarnings("resource")
  private static final GenericContainer<?> VALKEY =
      TestContainerImages.valkey().withExposedPorts(6379);

  private static RedisClient client;
  private static StatefulRedisConnection<String, String> connection;

  @BeforeAll
  static void start() {
    Docker.require();
    VALKEY.start();
    client = RedisClient.create("redis://" + VALKEY.getHost() + ":" + VALKEY.getMappedPort(6379));
    connection = client.connect();
  }

  @AfterAll
  static void stop() {
    if (connection != null) {
      connection.close();
      client.shutdown();
    }
    VALKEY.stop();
  }

  @Test
  void restartKeepsInProgressOwnershipAndTheExactCompletedResponse() throws Exception {
    String key = "restart-" + UUID.randomUUID();
    String uri = "redis://" + VALKEY.getHost() + ":" + VALKEY.getMappedPort(6379);
    var now = Instant.now();
    var record =
        IdempotencyRecord.builder(key, "request-hash", now.plusSeconds(300), now.plusSeconds(300))
            .status(Status.IN_PROGRESS)
            .owner("original-owner")
            .response(null)
            .resolutionVersion(0)
            .failure(null)
            .build();
    try (var before = client.connect()) {
      var store = new RedisIdempotencyStore(before.sync(), PREFIX);
      Assertions.assertTrue(store.tryStart(record));
    }
    // New transport/client and async implementation reopen the same external state.
    var restartedClient = RedisClient.create(uri);
    try (var after = restartedClient.connect()) {
      var store = new RedisAsyncIdempotencyStore(after.async(), PREFIX, Clock.systemUTC());
      var persisted =
          store.find(key, now).toCompletableFuture().get(5, TimeUnit.SECONDS).orElseThrow();
      Assertions.assertEquals(record, persisted);
      Assertions.assertFalse(store.tryStart(record).toCompletableFuture().get(5, TimeUnit.SECONDS));
      var response =
          new StoredResponse(201, Map.of("Location", "/payments/1"), new byte[] {0, 1, 127});
      Assertions.assertTrue(
          store
              .complete(key, "original-owner", response, now.plusSeconds(300))
              .toCompletableFuture()
              .get(5, TimeUnit.SECONDS));
    } finally {
      restartedClient.shutdown();
    }
    try (var finalConnection = client.connect()) {
      var done =
          new RedisIdempotencyStore(finalConnection.sync(), PREFIX).find(key, now).orElseThrow();
      Assertions.assertEquals(Status.COMPLETED, done.status());
      Assertions.assertEquals(201, Objects.requireNonNull(done.response()).status());
      Assertions.assertArrayEquals(
          new byte[] {0, 1, 127}, Objects.requireNonNull(done.response()).body());
    }
  }

  @Test
  void protectedReplayAndConcurrentCasKeepTheSameCiphertext() {
    ProtectedReplayChecks.verify(
        new RedisIdempotencyStore(connection.sync(), PREFIX),
        new RedisAsyncIdempotencyStore(connection.async(), PREFIX, Clock.systemUTC()),
        key -> connection.sync().get(PREFIX + key));
  }

  @TestFactory
  Stream<DynamicTest> contract() {
    RedisCommands<String, String> redis = connection.sync();
    IdempotencyStoreContract.Backend backend =
        new IdempotencyStoreContract.Backend() {
          @Override
          public IdempotencyStore create(MutableClock clock) {
            return new RedisIdempotencyStore(redis, PREFIX, clock);
          }

          @Override
          public void expire(IdempotencyStore store, String key, MutableClock clock)
              throws InterruptedException {
            // the server expires the key by itself: wait for it
            Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .until(() -> redis.exists(PREFIX + key) == 0);
            clock.advance(IdempotencyStoreContract.RETENTION.plusSeconds(1));
          }
        };
    return IdempotencyStoreContract.checks(backend).stream()
        .map(c -> DynamicTest.dynamicTest(c.name(), c::run));
  }
}
