package pe.axiz.adapter.observability.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

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
  void equalPoolNamesAreIsolatedByApplicationRegistryAndCleanupIsIdempotent() throws Exception {
    var firstRegistry = new SimpleMeterRegistry();
    var secondRegistry = new SimpleMeterRegistry();
    try {
      var first = new BoundedCompletionExecutor(1, 1);
      var second = new BoundedCompletionExecutor(1, 1);
      Runnable removeFirst = AwsCompletionMetrics.bind("same-pool", first, firstRegistry);
      Runnable removeSecond = AwsCompletionMetrics.bind("same-pool", second, secondRegistry);
      try {
        first.execute(() -> {});
        first.shutdown();
        assertTrue(first.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(
            1,
            firstRegistry
                .get("axiz.transport.latency")
                .tag("transport", "same-pool")
                .timer()
                .count());
        assertEquals(
            0,
            secondRegistry
                .get("axiz.transport.latency")
                .tag("transport", "same-pool")
                .timer()
                .count());
        removeFirst.run();
        removeFirst.run();
        assertNull(
            firstRegistry.find("axiz.transport.pending").tag("transport", "same-pool").gauge());
        assertNotNull(
            secondRegistry.find("axiz.transport.pending").tag("transport", "same-pool").gauge());
      } finally {
        first.shutdownNow();
        second.shutdownNow();
        removeFirst.run();
        removeSecond.run();
      }
    } finally {
      firstRegistry.close();
      secondRegistry.close();
    }
  }

  @Test
  void applicationBindingDoesNotRequireAddingTheRegistryToTheGlobalComposite() {
    var registry = new SimpleMeterRegistry();
    try (var owner =
        new AsyncAwsClients(
            StaticCredentialsProvider.create(AwsBasicCredentials.create("offline", "offline")),
            Clock.systemUTC(),
            Duration.ofDays(1),
            1,
            1)) {
      AwsCompletionMetrics.bind(owner, "application-only", registry);
      assertNotNull(
          registry.find("axiz.transport.pending").tag("transport", "application-only").gauge());
      assertNull(
          Metrics.globalRegistry
              .find("axiz.transport.pending")
              .tag("transport", "application-only")
              .gauge());
      owner.close();
      owner.close();
      assertNull(
          registry.find("axiz.transport.pending").tag("transport", "application-only").gauge());
      assertThrows(
          IllegalStateException.class, () -> AwsCompletionMetrics.bind(owner, "closed", registry));
      assertThrows(
          IllegalArgumentException.class, () -> AwsCompletionMetrics.create("", 1, 1, registry));
    } finally {
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
