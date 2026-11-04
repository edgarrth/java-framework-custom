package pe.axiz.core.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.BusinessException;

class LockTest {

  static final class MutableClock extends Clock {
    volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Test
  void leaseExclusivityRenewAndRelease() {
    MutableClock clock = new MutableClock();
    InMemoryDistributedLock lock = new InMemoryDistributedLock(clock);
    LockHandle a = lock.tryAcquire("account:1", Duration.ofSeconds(10)).orElseThrow();
    assertTrue(lock.tryAcquire("account:1", Duration.ofSeconds(10)).isEmpty());
    assertTrue(lock.tryAcquire("account:2", Duration.ofSeconds(10)).isPresent());
    assertTrue(a.renew(Duration.ofSeconds(10)));
    clock.now = clock.now.plusSeconds(11);
    LockHandle b = lock.tryAcquire("account:1", Duration.ofSeconds(10)).orElseThrow();
    assertFalse(a.renew(Duration.ofSeconds(10)), "expired owner cannot renew");
    a.release();
    assertTrue(
        lock.tryAcquire("account:1", Duration.ofSeconds(1)).isEmpty(),
        "stale owner cannot release the new owner");
    b.close();
    assertTrue(lock.tryAcquire("account:1", Duration.ofSeconds(1)).isPresent());
    assertEquals("account:1", b.resource());
  }

  @Test
  void templateSerializesWork() throws Exception {
    CountDownLatch attempted = new CountDownLatch(10);
    InMemoryDistributedLock store = new InMemoryDistributedLock(Clock.systemUTC());
    LockTemplate template =
        new LockTemplate(
            (resource, lease) -> {
              var acquired = store.tryAcquire(resource, lease);
              attempted.countDown();
              return acquired;
            },
            Duration.ofMillis(5));
    AtomicInteger concurrent = new AtomicInteger();
    AtomicInteger maxConcurrent = new AtomicInteger();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var results = new ArrayList<Future<?>>();
      for (int i = 0; i < 10; i++) {
        results.add(
            executor.submit(
                () ->
                    template.withLock(
                        "account:9",
                        Duration.ofMillis(300),
                        Duration.ofSeconds(5),
                        () -> {
                          maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                          assertTrue(attempted.await(5, TimeUnit.SECONDS));
                          concurrent.decrementAndGet();
                          return null;
                        })));
      }
      for (Future<?> result : results) result.get(10, TimeUnit.SECONDS);
    }
    assertEquals(1, maxConcurrent.get());
  }

  @Test
  void busyResourceIs409AndLeaseIsRenewedDuringLongWork() throws Exception {
    InMemoryDistributedLock lock = new InMemoryDistributedLock(Clock.systemUTC());
    LockTemplate template = new LockTemplate(lock, Duration.ofMillis(5));
    LockHandle held = lock.tryAcquire("r", Duration.ofSeconds(30)).orElseThrow();
    BusinessException e =
        assertThrows(
            BusinessException.class,
            () -> template.withLock("r", Duration.ofSeconds(1), Duration.ofMillis(30), () -> 1));
    assertEquals(409, e.category().httpStatus());
    held.release();
    String result =
        template.withLock(
            "r",
            Duration.ofMillis(60),
            Duration.ofSeconds(1),
            () -> {
              Thread.sleep(200); // longer than the lease: the renewer keeps it
              assertTrue(lock.tryAcquire("r", Duration.ofSeconds(1)).isEmpty());
              return "done";
            });
    assertEquals("done", result);
    assertTrue(lock.tryAcquire("r", Duration.ofSeconds(1)).isPresent());
  }
}
