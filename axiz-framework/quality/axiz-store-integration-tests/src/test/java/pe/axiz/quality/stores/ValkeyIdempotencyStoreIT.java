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
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (redis.exists(PREFIX + key) > 0) {
              if (System.nanoTime() > deadline) {
                throw new AssertionError("key did not expire: " + key);
              }
              Thread.sleep(50);
            }
            clock.advance(IdempotencyStoreContract.RETENTION.plusSeconds(1));
          }
        };
    return IdempotencyStoreContract.checks(backend).stream()
        .map(c -> DynamicTest.dynamicTest(c.name(), c::run));
  }
}
