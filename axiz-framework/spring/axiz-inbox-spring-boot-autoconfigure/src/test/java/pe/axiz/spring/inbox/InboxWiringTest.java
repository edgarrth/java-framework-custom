package pe.axiz.spring.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import pe.axiz.core.inbox.InboxStore;

/** La purga de las filas expiradas del inbox y el almacén DynamoDB. */
class InboxWiringTest {

  /** Cuenta las purgas; puede fallar bajo demanda. */
  static class PurgeCounter implements InboxStore {
    final AtomicInteger purges = new AtomicInteger();
    final AtomicBoolean failing = new AtomicBoolean();

    @Override
    public Acquisition tryAcquire(
        String consumer,
        String messageId,
        String owner,
        Instant now,
        Instant leaseUntil,
        Instant expiresAt) {
      return Acquisition.acquired();
    }

    @Override
    public boolean markProcessed(String consumer, String messageId, String owner, Instant at) {
      return true;
    }

    @Override
    public boolean release(String consumer, String messageId, String owner) {
      return true;
    }

    @Override
    public int purgeExpired(Instant now) {
      purges.incrementAndGet();
      if (failing.get()) {
        throw new IllegalStateException("database down");
      }
      return 2;
    }
  }

  private static void await(BooleanSupplier condition) throws Exception {
    Awaitility.await()
        .pollDelay(Duration.ZERO)
        .pollInterval(Duration.ofMillis(5))
        .atMost(Duration.ofSeconds(5))
        .until(condition::getAsBoolean);
  }

  @Test
  void expiredRowsArePurgedOnScheduleAndAFailedPurgeIsRetried() throws Exception {
    PurgeCounter store = new PurgeCounter();
    InboxPurger purger = new InboxPurger(store, Duration.ofMillis(5), Clock.systemUTC());
    assertFalse(purger.isRunning());
    purger.start();
    try {
      assertTrue(purger.isRunning());
      await(() -> store.purges.get() >= 2);
      store.failing.set(true);
      int failedFrom = store.purges.get();
      await(() -> store.purges.get() >= failedFrom + 2);
    } finally {
      purger.stop();
    }
    assertFalse(purger.isRunning());
  }

  @Test
  void aPurgeThatSwallowsTheInterruptStillEndsTheWorker() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<Thread> worker = new AtomicReference<>();
    PurgeCounter driverLike =
        new PurgeCounter() {
          @Override
          public int purgeExpired(Instant now) {
            calls.incrementAndGet();
            worker.set(Thread.currentThread());
            entered.countDown();
            try {
              new CountDownLatch(1).await();
            } catch (InterruptedException e) {
              // como algunos drivers JDBC: la interrupción se consume y la llamada simplemente
              // retorna
            }
            return 0;
          }
        };
    InboxPurger purger = new InboxPurger(driverLike, Duration.ofMillis(1), Clock.systemUTC());
    purger.start();
    assertTrue(entered.await(5, TimeUnit.SECONDS));
    purger.stop();
    Thread active = Objects.requireNonNull(worker.get());
    active.join(Duration.ofSeconds(2));
    assertFalse(active.isAlive(), "the purge worker must terminate after stop");
    assertEquals(1, calls.get(), "the stop flag ends the loop even without the interrupt");
  }
}
