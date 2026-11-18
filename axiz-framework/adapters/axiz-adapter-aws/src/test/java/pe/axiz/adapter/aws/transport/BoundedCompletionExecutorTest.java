package pe.axiz.adapter.aws.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BoundedCompletionExecutorTest {
  @Test
  void resourceLimitsIncludeBothEndpointsAndRejectValuesOutsideTheBudget() {
    assertEquals(1, BoundedCompletionExecutor.validWorkers(1, 1));
    assertEquals(64, BoundedCompletionExecutor.validWorkers(64, 65536));
    for (int workers : new int[] {0, 65})
      assertThrows(
          IllegalArgumentException.class, () -> BoundedCompletionExecutor.validWorkers(workers, 1));
    for (int capacity : new int[] {0, 65537})
      assertThrows(
          IllegalArgumentException.class,
          () -> BoundedCompletionExecutor.validWorkers(1, capacity));
  }

  @Test
  void saturationAndCompletionAreReportedWithoutRunningRejectedWork() throws Exception {
    var executor = new BoundedCompletionExecutor(1, 1);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var reported = new CountDownLatch(2);
    var rejections = new AtomicInteger();
    var totalNanos = new AtomicLong();
    executor.observe(
        new CompletionObserver() {
          @Override
          public void rejected() {
            rejections.incrementAndGet();
          }

          @Override
          public void completed(long nanos) {
            totalNanos.addAndGet(nanos);
            reported.countDown();
          }
        });
    long start = System.nanoTime();
    try {
      executor.execute(
          () -> {
            entered.countDown();
            try {
              release.await();
            } catch (InterruptedException failure) {
              Thread.currentThread().interrupt();
            }
          });
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      executor.execute(() -> {});
      assertThrows(
          RejectedExecutionException.class,
          () ->
              executor.execute(
                  () -> {
                    throw new AssertionError("Rejected work must never execute");
                  }));
      assertEquals(1, rejections.get());
      release.countDown();
      assertTrue(reported.await(3, TimeUnit.SECONDS));
      long elapsed = System.nanoTime() - start;
      assertTrue(totalNanos.get() > 0);
      assertTrue(totalNanos.get() <= elapsed * 2, "duration is elapsed time, not clock origin");
    } finally {
      release.countDown();
      executor.shutdown();
      assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
    }
  }

  @Test
  void admissionIsBoundedAndWorkersAreCreatedBeforeTheFirstSubmission() throws Exception {
    var executor = new BoundedCompletionExecutor();
    CountDownLatch entered = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(258);
    AtomicInteger count = new AtomicInteger();
    try {
      assertEquals(2, executor.getPoolSize());
      for (int i = 0; i < 2; i++)
        executor.execute(
            () -> {
              entered.countDown();
              try {
                release.await();
              } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
              }
              count.incrementAndGet();
              finished.countDown();
            });
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      for (int i = 0; i < 256; i++)
        executor.execute(
            () -> {
              count.incrementAndGet();
              finished.countDown();
            });
      assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
      assertEquals(256, executor.getQueueSize());
      release.countDown();
      assertTrue(finished.await(3, TimeUnit.SECONDS));
      assertEquals(258, count.get());
      CountDownLatch next = new CountDownLatch(1);
      executor.execute(next::countDown);
      assertTrue(next.await(3, TimeUnit.SECONDS), "completed callbacks release admission capacity");
    } finally {
      release.countDown();
      executor.shutdown();
      assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
    }
    assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
    assertThrows(NullPointerException.class, () -> executor.execute(null));
  }
}
