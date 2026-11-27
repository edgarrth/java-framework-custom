package pe.axiz.spring.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.spring.foundation.lifecycle.WorkerLifecycle;

class InboxPurgerTest {
  @Test
  void zeroOrNegativeIntervalsCannotCreateAnUnboundedPurgeLoop() {
    InboxStore store = mock(InboxStore.class);
    for (Duration interval : List.of(Duration.ZERO, Duration.ofMillis(-1))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new InboxPurger(store, interval, Clock.systemUTC()));
    }
  }

  @Test
  void transientStoreFailureKeepsTheWorkerAliveAndStopJoinsIt() throws Exception {
    Instant now = Instant.parse("2026-11-18T12:00:00Z");
    InboxStore store = mock(InboxStore.class);
    var attempted = new AtomicInteger();
    var recovered = new CountDownLatch(1);
    when(store.purgeExpired(now))
        .thenAnswer(
            invocation -> {
              if (attempted.incrementAndGet() == 1)
                throw new IllegalStateException("Synthetic storage failure");
              recovered.countDown();
              return 1;
            });
    var purger = new InboxPurger(store, Duration.ofMillis(1), Clock.fixed(now, ZoneOffset.UTC));
    purger.start();
    try {
      assertTrue(recovered.await(5, TimeUnit.SECONDS));
      assertEquals(WorkerLifecycle.State.RUNNING, purger.workerSnapshot().state());
      assertEquals(1, purger.workerSnapshot().alive());
    } finally {
      purger.stop();
    }
    assertFalse(purger.isRunning());
    assertEquals(WorkerLifecycle.State.STOPPED, purger.workerSnapshot().state());
    assertEquals(0, purger.workerSnapshot().alive());
    assertEquals(Duration.ZERO, purger.shutdownGrace());
  }

  @Test
  void stopCallbackWaitsUntilAnUncooperativePurgeActuallyReturns() throws Exception {
    Instant now = Instant.parse("2026-11-19T12:00:00Z");
    InboxStore store = mock(InboxStore.class);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var stopped = new CountDownLatch(1);
    when(store.purgeExpired(now))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              boolean interrupted = false;
              while (true) {
                try {
                  release.await();
                  break;
                } catch (InterruptedException ignored) {
                  interrupted = true;
                }
              }
              if (interrupted) Thread.currentThread().interrupt();
              return 0;
            });
    var purger = new InboxPurger(store, Duration.ofMillis(1), Clock.fixed(now, ZoneOffset.UTC));
    purger.start();
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      purger.stop(stopped::countDown);
      assertEquals(WorkerLifecycle.State.STOPPING, purger.workerSnapshot().state());
      assertEquals(1, purger.workerSnapshot().alive());
      assertEquals(1, stopped.getCount());
      release.countDown();
      assertTrue(stopped.await(5, TimeUnit.SECONDS));
      assertFalse(purger.isRunning());
      assertEquals(0, purger.workerSnapshot().alive());
    } finally {
      release.countDown();
      purger.stop();
    }
  }
}
