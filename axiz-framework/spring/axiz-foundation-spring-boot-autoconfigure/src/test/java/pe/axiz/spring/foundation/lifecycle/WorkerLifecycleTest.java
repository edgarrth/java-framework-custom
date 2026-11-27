package pe.axiz.spring.foundation.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkerLifecycleTest {
  @Test
  void repeatedStartAndStopCannotDuplicateWorkersOrConfirmBeforeTheirActualEnd() throws Exception {
    var entered = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var stopped = new CountDownLatch(2);
    var runs = new AtomicInteger();
    var cleanups = new AtomicInteger();
    var lifecycle =
        new WorkerLifecycle(
            "test-worker",
            Duration.ZERO,
            () ->
                List.of(
                    () -> {
                      runs.incrementAndGet();
                      entered.countDown();
                      while (release.getCount() != 0) {
                        try {
                          release.await();
                        } catch (InterruptedException ignored) {
                          interrupted.countDown();
                        }
                      }
                    }),
            cleanups::incrementAndGet);
    lifecycle.start();
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      lifecycle.start();
      lifecycle.stop(stopped::countDown);
      lifecycle.stop(stopped::countDown);
      assertTrue(interrupted.await(5, TimeUnit.SECONDS));
      assertThrows(IllegalStateException.class, lifecycle::start);
      assertEquals(WorkerLifecycle.State.STOPPING, lifecycle.state());
      assertEquals(1, runs.get());
      assertEquals(2, stopped.getCount());
      assertFalse(lifecycle.accepting());
      assertTrue(lifecycle.isRunning());
      assertEquals(0, cleanups.get());
    } finally {
      release.countDown();
    }
    assertTrue(stopped.await(5, TimeUnit.SECONDS));
    assertFalse(lifecycle.isRunning());
    assertEquals(1, cleanups.get());
    lifecycle.start();
    lifecycle.stop();
    assertEquals(2, runs.get());
    assertEquals(2, cleanups.get());
  }

  @Test
  void failedResourceCleanupCannotBeReportedAsStoppedOrStartAnotherWorker() throws Exception {
    var cleanupAttempted = new CountDownLatch(1);
    var callbacks = new AtomicInteger();
    var runs = new AtomicInteger();
    var lifecycle =
        new WorkerLifecycle(
            "cleanup-failure",
            Duration.ZERO,
            () -> List.of(runs::incrementAndGet),
            () -> {
              cleanupAttempted.countDown();
              throw new IllegalStateException("synthetic resource still alive");
            });
    lifecycle.start();
    assertTrue(cleanupAttempted.await(5, TimeUnit.SECONDS));
    lifecycle.stop(callbacks::incrementAndGet);
    assertThrows(IllegalStateException.class, lifecycle::start);
    assertThrows(IllegalStateException.class, lifecycle::stop);
    assertEquals(WorkerLifecycle.State.FAILED, lifecycle.state());
    assertEquals(1, runs.get());
    assertEquals(0, callbacks.get());
    assertTrue(lifecycle.isRunning());
  }
}
