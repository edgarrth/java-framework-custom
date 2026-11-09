package pe.axiz.adapter.observability.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AwsCompletionExecutorTest {
  @Test
  void configuredAdmissionRejectsImmediatelyAndMetricsAreOwnedByLivePools() throws Exception {
    var registry = new SimpleMeterRegistry();
    Metrics.addRegistry(registry);
    var pool = new BoundedCompletionExecutor(1, 1);
    var other = new BoundedCompletionExecutor(1, 1);
    Runnable remove = AwsCompletionMetrics.bind("test-aws-completion", pool);
    Runnable removeOther = AwsCompletionMetrics.bind("test-aws-completion", other);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var finished = new CountDownLatch(2);
    try {
      pool.execute(
          () -> {
            started.countDown();
            try {
              release.await();
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            } finally {
              finished.countDown();
            }
          });
      assertTrue(started.await(5, TimeUnit.SECONDS));
      pool.execute(finished::countDown);
      assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> {}));
      assertEquals(
          1.0,
          registry
              .get("axiz.transport.queued")
              .tag("transport", "test-aws-completion")
              .gauge()
              .value());
      assertEquals(
          1.0,
          registry
              .get("axiz.transport.active")
              .tag("transport", "test-aws-completion")
              .gauge()
              .value());
      assertEquals(
          2.0,
          registry
              .get("axiz.transport.pending")
              .tag("transport", "test-aws-completion")
              .gauge()
              .value());
      assertEquals(
          1.0,
          registry
              .get("axiz.transport.rejected")
              .tag("transport", "test-aws-completion")
              .counter()
              .count());
      release.countDown();
      assertTrue(finished.await(5, TimeUnit.SECONDS));
      pool.shutdown();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
      assertEquals(
          2L,
          registry
              .get("axiz.transport.latency")
              .tag("transport", "test-aws-completion")
              .timer()
              .count());
      remove.run();
      assertNotNull(
          registry.find("axiz.transport.queued").tag("transport", "test-aws-completion").gauge());
      removeOther.run();
      assertNull(
          registry.find("axiz.transport.queued").tag("transport", "test-aws-completion").gauge());
    } finally {
      release.countDown();
      pool.shutdownNow();
      other.shutdownNow();
      remove.run();
      removeOther.run();
      Metrics.removeRegistry(registry);
      registry.close();
    }
  }

  @Test
  void boundsAreValidatedBeforeStartingWorkers() {
    for (int[] invalid : new int[][] {{0, 1}, {65, 1}, {1, 0}, {1, 65537}})
      assertThrows(
          IllegalArgumentException.class,
          () -> new BoundedCompletionExecutor(invalid[0], invalid[1]));
    assertEquals(64, BoundedCompletionExecutor.validWorkers(64, 65536));
    var defaults = new BoundedCompletionExecutor();
    try {
      assertEquals(2, defaults.getCorePoolSize());
    } finally {
      defaults.shutdownNow();
    }
  }
}
