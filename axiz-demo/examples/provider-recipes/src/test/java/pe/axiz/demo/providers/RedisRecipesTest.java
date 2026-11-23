package pe.axiz.demo.providers;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import pe.axiz.core.cache.AsyncByteCache;
import pe.axiz.core.cache.ByteCache;
import pe.axiz.core.idempotency.AsyncIdempotencyStore;
import pe.axiz.core.idempotency.IdempotencyRecord;
import pe.axiz.core.idempotency.IdempotencyStore;
import pe.axiz.core.idempotency.StoredResponse;
import pe.axiz.core.lock.AsyncDistributedLock;
import pe.axiz.core.lock.DistributedLock;
import pe.axiz.core.security.AsyncRateLimiter;
import pe.axiz.core.security.RateLimiter;
import pe.axiz.spring.cache.redis.AxizCacheRedisAutoConfiguration;
import pe.axiz.spring.idempotency.redis.AxizIdempotencyRedisAutoConfiguration;
import pe.axiz.spring.lock.redis.AxizLockRedisAutoConfiguration;
import pe.axiz.spring.security.redis.AxizSecurityRedisAutoConfiguration;
import pe.axiz.spring.test.AxizContainers;

/**
 * Contratos públicos respaldados por Valkey; sin mocks y sin recurrir en silencio a un proveedor en
 * memoria.
 */
class RedisRecipesTest {
  @Test
  void distributedProvidersWorkOnBothStacksAndCannotBeReplacedByDeclarations(
      TestReporter evidence) {
    try (var redis = AxizContainers.valkey()) {
      redis.start();
      var configurations =
          AutoConfigurations.of(
              AxizCacheRedisAutoConfiguration.class,
              AxizIdempotencyRedisAutoConfiguration.class,
              AxizLockRedisAutoConfiguration.class,
              AxizSecurityRedisAutoConfiguration.class);
      String[] properties = {
        "axiz.cache.redis=true",
        "axiz.idempotency.store=redis",
        "axiz.lock.store=redis",
        "axiz.security.rate-limit.store=redis",
        "axiz.security.rate-limit.enabled=true"
      };
      var imperative =
          new ApplicationContextRunner()
              .withConfiguration(configurations)
              .withBean(
                  LettuceConnectionFactory.class,
                  () -> new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379)))
              .withPropertyValues(properties);
      imperative.run(
          context -> {
            assertThat(context).hasNotFailed();
            byte[] value = "non-sensitive-view".getBytes(StandardCharsets.UTF_8);
            var cache = context.getBean(ByteCache.class);
            cache.put("recipes:customer:a", value, Duration.ofMinutes(1));
            assertThat(cache.get("recipes:customer:a"))
                .hasValueSatisfying(bytes -> assertThat(bytes).isEqualTo(value));
            cache.evict("recipes:customer:a");
            assertThat(cache.get("recipes:customer:a")).isEmpty();

            var locks = context.getBean(DistributedLock.class);
            try (var first =
                locks.tryAcquire("recipe-ledger", Duration.ofMinutes(1)).orElseThrow()) {
              assertThat(locks.tryAcquire("recipe-ledger", Duration.ofMinutes(1))).isEmpty();
              assertThat(first.renew(Duration.ofMinutes(1))).isTrue();
            }
            locks.tryAcquire("recipe-ledger", Duration.ofMinutes(1)).orElseThrow().close();

            var store = context.getBean(IdempotencyStore.class);
            var now = Instant.now();
            var record =
                IdempotencyRecord.builder(
                        "tenant-a:payment:request-1",
                        "request-hash",
                        now.plusSeconds(60),
                        now.plusSeconds(3600))
                    .owner("worker-a")
                    .build();
            assertThat(store.tryStart(record)).isTrue();
            assertThat(store.tryStart(record)).isFalse();
            var response =
                new StoredResponse(
                    201,
                    Map.of("Content-Type", "application/json"),
                    "{\"id\":\"receipt-1\"}".getBytes(StandardCharsets.UTF_8));
            assertThat(store.complete(record.key(), "worker-b", response, now.plusSeconds(3600)))
                .isFalse();
            assertThat(store.complete(record.key(), "worker-a", response, now.plusSeconds(3600)))
                .isTrue();
            assertThat(store.find(record.key(), now).orElseThrow().response()).isEqualTo(response);
            var limiter = context.getBean(RateLimiter.class);
            assertThat(limiter.tryAcquire("verified-subject:a", 1, Duration.ofMinutes(1)).allowed())
                .isTrue();
            assertThat(limiter.tryAcquire("verified-subject:a", 1, Duration.ofMinutes(1)).allowed())
                .isFalse();
            evidence.publishEntry("axiz.capability.cache", "servlet,redis");
            evidence.publishEntry("axiz.capability.cache-redis", "servlet,redis");
            evidence.publishEntry("axiz.capability.idempotency", "servlet,redis");
            evidence.publishEntry("axiz.capability.idempotency-redis", "servlet,redis");
            evidence.publishEntry("axiz.capability.lock", "servlet,redis");
            evidence.publishEntry("axiz.capability.lock-redis", "servlet,redis");
            evidence.publishEntry("axiz.capability.security-redis", "servlet,redis");
          });
      new ReactiveWebApplicationContextRunner()
          .withConfiguration(configurations)
          .withBean(
              LettuceConnectionFactory.class,
              () -> new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379)))
          .withPropertyValues(properties)
          .run(
              context -> {
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(ByteCache.class)
                    .doesNotHaveBean(DistributedLock.class)
                    .doesNotHaveBean(IdempotencyStore.class);
                var cache = context.getBean(AsyncByteCache.class);
                cache
                    .put("recipes:async:a", new byte[] {1, 2}, Duration.ofMinutes(1))
                    .toCompletableFuture()
                    .join();
                assertThat(cache.get("recipes:async:a").toCompletableFuture().join()).isPresent();
                cache.evict("recipes:async:a").toCompletableFuture().join();
                assertThat(cache.get("recipes:async:a").toCompletableFuture().join()).isEmpty();
                var locks = context.getBean(AsyncDistributedLock.class);
                var first =
                    locks
                        .tryAcquire("async-ledger", Duration.ofMinutes(1))
                        .toCompletableFuture()
                        .join()
                        .orElseThrow();
                try {
                  assertThat(
                          locks
                              .tryAcquire("async-ledger", Duration.ofMinutes(1))
                              .toCompletableFuture()
                              .join())
                      .isEmpty();
                } finally {
                  first.release().toCompletableFuture().join();
                }
                assertThat(
                        context
                            .getBean(AsyncIdempotencyStore.class)
                            .find("tenant-a:payment:request-1", Instant.now())
                            .toCompletableFuture()
                            .join()
                            .orElseThrow()
                            .status())
                    .isEqualTo(IdempotencyRecord.Status.COMPLETED);
                var limiter = context.getBean(AsyncRateLimiter.class);
                assertThat(
                        limiter
                            .tryAcquire("verified-subject:a", 1, Duration.ofMinutes(1), false)
                            .toCompletableFuture()
                            .join()
                            .allowed())
                    .isFalse();
                evidence.publishEntry("axiz.capability.cache", "reactive,redis");
                evidence.publishEntry("axiz.capability.cache-redis", "reactive,redis");
                evidence.publishEntry("axiz.capability.idempotency", "reactive,redis");
                evidence.publishEntry("axiz.capability.idempotency-redis", "reactive,redis");
                evidence.publishEntry("axiz.capability.lock", "reactive,redis");
                evidence.publishEntry("axiz.capability.lock-redis", "reactive,redis");
                evidence.publishEntry("axiz.capability.security-redis", "reactive,redis");
              });
      // Testigos negativos: una declaración YAML no puede contar como capacidad ejecutada.
      imperative
          .withClassLoader(new FilteredClassLoader("pe.axiz.adapter.cache.redis"))
          .run(context -> assertThat(context).doesNotHaveBean(ByteCache.class));
      imperative
          .withPropertyValues("axiz.cache.enabled=false")
          .run(context -> assertThat(context).doesNotHaveBean(ByteCache.class));
    }
  }
}
