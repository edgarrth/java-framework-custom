package pe.axiz.adapter.observability.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoundedCompletionExecutorTest {
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
      assertEquals(256, executor.getQueue().size());
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
