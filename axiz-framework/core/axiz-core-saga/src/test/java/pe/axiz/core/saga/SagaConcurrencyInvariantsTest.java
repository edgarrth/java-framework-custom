package pe.axiz.core.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
    var original =
        SagaState.builder()
            .sagaId("id")
            .sagaName("payment")
            .status(SagaStatus.RUNNING)
            .stepIndex(0)
            .context("")
            .error(null)
            .updatedAt(now)
            .version(0)
            .build();
    store.create(original);
    assertThrows(IllegalStateException.class, () -> store.create(original));
    var next =
        SagaState.builder()
            .sagaId("id")
            .sagaName("payment")
            .status(SagaStatus.RUNNING)
            .stepIndex(1)
            .context("done")
            .error(null)
            .updatedAt(now)
            .version(1)
            .build();
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
    assertEquals(List.of(next), store.findStale(now.plusNanos(1), 10));
    store.update(
        SagaState.builder()
            .sagaId("id")
            .sagaName("payment")
            .status(SagaStatus.COMPLETED)
            .stepIndex(1)
            .context("done")
            .error(null)
            .updatedAt(now)
            .version(2)
            .build());
    assertTrue(store.findStale(now.plusSeconds(1), 10).isEmpty());
  }
}
