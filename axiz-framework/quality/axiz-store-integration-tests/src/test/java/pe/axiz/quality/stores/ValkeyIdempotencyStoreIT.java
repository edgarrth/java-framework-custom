package pe.axiz.quality.stores;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import pe.axiz.adapter.idempotency.redis.RedisIdempotencyStore;
import pe.axiz.core.idempotency.IdempotencyStore;
import pe.axiz.test.contracts.IdempotencyStoreContract;
import pe.axiz.test.support.MutableClock;

/** Idempotency store contract on a real Valkey (ElastiCache): Lua scripts and server TTL. */
class ValkeyIdempotencyStoreIT {

  private static final String PREFIX = "axiz:it:idem:";

  @SuppressWarnings("resource")
  private static final GenericContainer<?> VALKEY =
      new GenericContainer<>(DockerImageName.parse(Docker.VALKEY)).withExposedPorts(6379);

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

  @org.junit.jupiter.api.Test
  void restartKeepsInProgressOwnershipAndTheExactCompletedResponse() throws Exception {
    String key = "restart-" + java.util.UUID.randomUUID();
    String uri = "redis://" + VALKEY.getHost() + ":" + VALKEY.getMappedPort(6379);
    var now = java.time.Instant.now();
    var record =
        new pe.axiz.core.idempotency.IdempotencyRecord(
            key,
            "request-hash",
            pe.axiz.core.idempotency.IdempotencyRecord.Status.IN_PROGRESS,
            "original-owner",
            null,
            now.plusSeconds(300));
    try (var before = client.connect()) {
      var store = new RedisIdempotencyStore(before.sync(), PREFIX);
      org.junit.jupiter.api.Assertions.assertTrue(store.tryStart(record));
    }
    // New transport/client and async implementation reopen the same external state.
    var restartedClient = RedisClient.create(uri);
    try (var after = restartedClient.connect()) {
      var store =
          new pe.axiz.adapter.idempotency.redis.RedisAsyncIdempotencyStore(
              after.async(), PREFIX, java.time.Clock.systemUTC());
      var persisted =
          store
              .find(key, now)
              .toCompletableFuture()
              .get(5, java.util.concurrent.TimeUnit.SECONDS)
              .orElseThrow();
      org.junit.jupiter.api.Assertions.assertEquals(record, persisted);
      org.junit.jupiter.api.Assertions.assertFalse(
          store
              .tryStart(record)
              .toCompletableFuture()
              .get(5, java.util.concurrent.TimeUnit.SECONDS));
      var response =
          new pe.axiz.core.idempotency.StoredResponse(
              201, java.util.Map.of("Location", "/payments/1"), new byte[] {0, 1, 127});
      org.junit.jupiter.api.Assertions.assertTrue(
          store
              .complete(key, "original-owner", response, now.plusSeconds(300))
              .toCompletableFuture()
              .get(5, java.util.concurrent.TimeUnit.SECONDS));
    } finally {
      restartedClient.shutdown();
    }
    try (var finalConnection = client.connect()) {
      var done =
          new RedisIdempotencyStore(finalConnection.sync(), PREFIX).find(key, now).orElseThrow();
      org.junit.jupiter.api.Assertions.assertEquals(
          pe.axiz.core.idempotency.IdempotencyRecord.Status.COMPLETED, done.status());
      org.junit.jupiter.api.Assertions.assertEquals(
          201, java.util.Objects.requireNonNull(done.response()).status());
      org.junit.jupiter.api.Assertions.assertArrayEquals(
          new byte[] {0, 1, 127}, java.util.Objects.requireNonNull(done.response()).body());
    }
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
            org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .until(() -> redis.exists(PREFIX + key) == 0);
            clock.advance(IdempotencyStoreContract.RETENTION.plusSeconds(1));
          }
        };
    return IdempotencyStoreContract.checks(backend).stream()
        .map(c -> DynamicTest.dynamicTest(c.name(), c::run));
  }
}
