package pe.axiz.core.commons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BoundedCallbackExecutorTest {
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
      assertThrows(
          java.util.concurrent.ExecutionException.class, () -> first.get(3, TimeUnit.SECONDS));
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
    var started = new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.ThreadFactory threads =
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
