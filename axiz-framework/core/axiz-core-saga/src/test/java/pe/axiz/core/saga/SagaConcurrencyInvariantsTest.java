package pe.axiz.core.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SagaConcurrencyInvariantsTest {
  @Test
  void onlyOneConcurrentRecoveryCanAdvanceTheSameVersion() throws Exception {
    var store = new InMemorySagaStateStore();
    Instant now = Instant.parse("2026-11-05T00:00:00Z");
    var original = new SagaState("id", "payment", SagaStatus.RUNNING, 0, "", null, now, 0);
    store.create(original);
    assertThrows(IllegalStateException.class, () -> store.create(original));
    var next = new SagaState("id", "payment", SagaStatus.RUNNING, 1, "done", null, now, 1);
    var start = new CountDownLatch(1);
    var advanced = new AtomicInteger();
    var conflicts = new AtomicInteger();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var attempts = new ArrayList<Future<?>>();
      for (int n = 0; n < 16; n++)
        attempts.add(
            executor.submit(
                () -> {
                  assertTrue(start.await(5, TimeUnit.SECONDS));
                  try {
                    store.update(next);
                    advanced.incrementAndGet();
                  } catch (SagaConcurrencyException expected) {
                    conflicts.incrementAndGet();
                  }
                  return null;
                }));
      start.countDown();
      for (var attempt : attempts) attempt.get(10, TimeUnit.SECONDS);
    }
    assertEquals(1, advanced.get());
    assertEquals(15, conflicts.get());
    assertEquals(next, store.find("id").orElseThrow());
    assertTrue(store.findStale(now, 10).isEmpty());
    assertEquals(java.util.List.of(next), store.findStale(now.plusNanos(1), 10));
    store.update(new SagaState("id", "payment", SagaStatus.COMPLETED, 1, "done", null, now, 2));
    assertTrue(store.findStale(now.plusSeconds(1), 10).isEmpty());
  }
}
