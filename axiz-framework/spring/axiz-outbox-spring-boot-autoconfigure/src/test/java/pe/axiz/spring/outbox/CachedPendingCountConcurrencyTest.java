package pe.axiz.spring.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import pe.axiz.core.outbox.OutboxStore;

class CachedPendingCountConcurrencyTest {
  @Test
  void aRefreshFinishingDuringScrapeDoesNotTriggerAnotherQuery() throws Exception {
    Thread scrape = Thread.currentThread();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var worker = new AtomicReference<Thread>();
    var pauseNextRead = new AtomicBoolean();
    var queries = new AtomicInteger();
    Clock clock =
        new Clock() {
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
            if (Thread.currentThread() == scrape && pauseNextRead.compareAndSet(true, false)) {
              release.countDown();
              try {
                worker.get().join(5000);
                assertFalse(worker.get().isAlive(), "background refresh did not complete");
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
              }
            }
            return Instant.parse("2026-10-31T10:00:00Z");
          }
        };
    OutboxStore store =
        (OutboxStore)
            java.lang.reflect.Proxy.newProxyInstance(
                OutboxStore.class.getClassLoader(),
                new Class<?>[] {OutboxStore.class},
                (proxy, method, args) -> {
                  if (!method.getName().equals("countPending"))
                    throw new UnsupportedOperationException(method.getName());
                  assertFalse(
                      Thread.currentThread() == scrape,
                      "database I/O must stay off the scrape thread");
                  worker.compareAndSet(null, Thread.currentThread());
                  int query = queries.incrementAndGet();
                  entered.countDown();
                  assertTrue(release.await(5, TimeUnit.SECONDS));
                  return 40L + query;
                });
    var pending = new CachedPendingCount(store, Duration.ofMinutes(1), clock);
    try {
      assertTrue(Double.isNaN(pending.value()));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      // The next scrape reads the old sample, then the first refresh completes before it can act.
      pauseNextRead.set(true);
      assertEquals(41.0, pending.value());
      assertEquals(41.0, pending.value());
      assertEquals(1, queries.get());
    } finally {
      release.countDown();
    }
  }
}
