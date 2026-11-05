package pe.axiz.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RateLimitConcurrencyInvariantsTest {
  static class MutableClock extends Clock {
    volatile Instant now = Instant.ofEpochSecond(1);

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aWindowAdmitsExactlyTheConfiguredQuotaUnderContention(boolean nativeStore) throws Exception {
    var clock = new MutableClock();
    var sync = new InMemoryRateLimiter(clock);
    var async = new InMemoryAsyncRateLimiter(clock);
    RateLimiter limiter =
        nativeStore
            ? (key, limit, window) ->
                async.tryAcquire(key, limit, window, false).toCompletableFuture().join()
            : sync;
    var start = new CountDownLatch(1);
    var allowed = new AtomicInteger();
    Duration window = Duration.ofSeconds(10);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var attempts = new ArrayList<Future<?>>();
      for (int n = 0; n < 100; n++)
        attempts.add(
            executor.submit(
                () -> {
                  assertTrue(start.await(5, TimeUnit.SECONDS));
                  var decision = limiter.tryAcquire("client", 7, window);
                  if (decision.allowed()) allowed.incrementAndGet();
                  assertTrue(decision.remaining() >= 0);
                  return null;
                }));
      start.countDown();
      for (var attempt : attempts) attempt.get(10, TimeUnit.SECONDS);
    }
    assertEquals(7, allowed.get());
    assertFalse(limiter.tryAcquire("client", 7, window).allowed());
    assertTrue(limiter.tryAcquire("other-client", 7, window).allowed());
    clock.now = Instant.ofEpochSecond(10);
    var reset = limiter.tryAcquire("client", 7, window);
    assertTrue(reset.allowed());
    assertEquals(6, reset.remaining());
    assertEquals(Duration.ZERO, reset.retryAfter());
  }
}
