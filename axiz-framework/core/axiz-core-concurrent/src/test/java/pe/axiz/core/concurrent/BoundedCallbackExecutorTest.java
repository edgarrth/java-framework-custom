package pe.axiz.core.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoundedCallbackExecutorTest {
  @Test
  void shutdownReportsOutstandingCallbacksAndTerminationWithoutClaimingEarlySuccess()
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var executor = new BoundedCallbackExecutor(1, 1, Thread.ofVirtual().factory(), "shutdown");
    try {
      assertFalse(executor.isShutdown());
      assertFalse(executor.isTerminated());
      executor.execute(
          () -> {
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
          });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var queued = new AtomicInteger();
      executor.execute(queued::incrementAndGet);
      assertEquals(1, executor.getActiveCount());
      assertEquals(1, executor.getQueueSize());
      assertEquals(1, executor.shutdownNow().size());
      assertTrue(executor.isShutdown());
      assertFalse(executor.isTerminated());
      assertFalse(executor.awaitTermination(1, TimeUnit.MILLISECONDS));
      assertEquals(0, queued.get());
      release.countDown();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      assertTrue(executor.isTerminated());
    } finally {
      release.countDown();
      executor.close();
    }
    try (var noQueue =
        new BoundedCallbackExecutor(1, 0, Thread.ofVirtual().factory(), "zero queue")) {
      assertEquals("accepted", noQueue.submit(() -> "accepted").get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void prestartsWorkersBoundsAllOutstandingWorkAndReleasesCapacityAfterFailure() throws Exception {
    var hold = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    try (var executor =
        new BoundedCallbackExecutor(
            1,
            1,
            Thread.ofPlatform().daemon().name("callback-test-", 0).factory(),
            "Test callbacks")) {
      assertEquals(1, executor.getPoolSize());
      var first =
          executor.submit(
              () -> {
                entered.countDown();
                assertTrue(hold.await(3, TimeUnit.SECONDS));
                throw new IllegalStateException("callback failed");
              });
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      var second = executor.submit(() -> Thread.currentThread().getName());
      assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
      assertFalse(second.isDone());
      hold.countDown();
      assertThrows(ExecutionException.class, () -> first.get(3, TimeUnit.SECONDS));
      assertTrue(second.get(3, TimeUnit.SECONDS).startsWith("callback-test-"));
      assertEquals("reused", executor.submit(() -> "reused").get(3, TimeUnit.SECONDS));
      executor.shutdown();
      assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
    } finally {
      hold.countDown();
    }
  }

  @Test
  void rejectsInvalidBoundsBeforeStartingAnyWorker() {
    var started = new AtomicInteger();
    ThreadFactory threads =
        work -> {
          started.incrementAndGet();
          return new Thread(work);
        };
    assertThrows(
        IllegalArgumentException.class,
        () -> new BoundedCallbackExecutor(0, 1, threads, "invalid"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BoundedCallbackExecutor(1, -1, threads, "invalid"));
    assertThrows(
        ArithmeticException.class,
        () -> new BoundedCallbackExecutor(1, Integer.MAX_VALUE, threads, "overflow"));
    assertEquals(0, started.get());
    assertThrows(
        IllegalStateException.class,
        () -> new BoundedCallbackExecutor(1, 1, work -> null, "unavailable"));
  }
}
