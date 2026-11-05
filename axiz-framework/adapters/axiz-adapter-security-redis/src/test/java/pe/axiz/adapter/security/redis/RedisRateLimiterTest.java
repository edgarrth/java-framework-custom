package pe.axiz.adapter.security.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import pe.axiz.core.security.RateLimitUnavailableException;

class RedisRateLimiterTest {

  @SuppressWarnings("unchecked")
  private static RedisCommands<String, String> fake(AtomicLong counter, boolean down) {
    return (RedisCommands<String, String>)
        Proxy.newProxyInstance(
            RedisCommands.class.getClassLoader(),
            new Class<?>[] {RedisCommands.class},
            (proxy, method, args) -> {
              if (method.getName().equals("eval")) {
                if (down) {
                  throw new RedisException("down");
                }
                assertEquals(ScriptOutputType.MULTI, args[1]);
                assertEquals("rl:test:k", ((String[]) args[2])[0]);
                return List.of(counter.incrementAndGet(), 900L);
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  @Test
  void allowsUpToLimitThenRejectsWithRetryAfter() {
    RedisRateLimiter limiter =
        new RedisRateLimiter(fake(new AtomicLong(), false), "rl:test:", false);
    assertTrue(limiter.tryAcquire("k", 2, Duration.ofSeconds(1)).allowed());
    assertTrue(limiter.tryAcquire("k", 2, Duration.ofSeconds(1)).allowed());
    var rejected = limiter.tryAcquire("k", 2, Duration.ofSeconds(1));
    assertFalse(rejected.allowed());
    assertEquals(Duration.ofMillis(900), rejected.retryAfter());
  }

  @Test
  void failurePolicy() {
    assertTrue(
        new RedisRateLimiter(fake(new AtomicLong(), true), "rl:test:", true)
            .tryAcquire("k", 1, Duration.ofSeconds(1))
            .allowed());
    assertThrows(
        RateLimitUnavailableException.class,
        () ->
            new RedisRateLimiter(fake(new AtomicLong(), true), "rl:test:", false)
                .tryAcquire("k", 1, Duration.ofSeconds(1)));
  }

  @Test
  void aRouteGroupCanChooseItsOwnFailurePolicy() {
    RedisRateLimiter openByDefault =
        new RedisRateLimiter(fake(new AtomicLong(), true), "rl:", true);
    assertThrows(
        RateLimitUnavailableException.class,
        () -> openByDefault.tryAcquire("k", 1, Duration.ofSeconds(1), false));
    RedisRateLimiter closedByDefault =
        new RedisRateLimiter(fake(new AtomicLong(), true), "rl:", false);
    assertTrue(closedByDefault.tryAcquire("k", 1, Duration.ofSeconds(1), true).allowed());
  }

  @Test
  @SuppressWarnings("unchecked")
  void nativeAsyncLimitsAndFailurePolicies() {
    var commands =
        pe.axiz.test.support.ImmediateAsyncFakes.adapt(
            io.lettuce.core.api.async.RedisAsyncCommands.class, fake(new AtomicLong(), false));
    var limiter = new RedisAsyncRateLimiter(commands, "rl:test:");
    assertTrue(
        limiter
            .tryAcquire("k", 2, Duration.ofSeconds(1), false)
            .toCompletableFuture()
            .join()
            .allowed());
    assertTrue(
        limiter
            .tryAcquire("k", 2, Duration.ofSeconds(1), false)
            .toCompletableFuture()
            .join()
            .allowed());
    var denied =
        limiter.tryAcquire("k", 2, Duration.ofSeconds(1), false).toCompletableFuture().join();
    assertFalse(denied.allowed());
    assertEquals(Duration.ofMillis(900), denied.retryAfter());
    var down =
        new RedisAsyncRateLimiter(
            pe.axiz.test.support.ImmediateAsyncFakes.adapt(
                io.lettuce.core.api.async.RedisAsyncCommands.class, fake(new AtomicLong(), true)),
            "rl:test:");
    assertTrue(
        down.tryAcquire("k", 1, Duration.ofSeconds(1), true)
            .toCompletableFuture()
            .join()
            .allowed());
    assertThrows(
        java.util.concurrent.CompletionException.class,
        () -> down.tryAcquire("k", 1, Duration.ofSeconds(1), false).toCompletableFuture().join());
  }
}
