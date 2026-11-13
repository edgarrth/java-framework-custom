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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import pe.axiz.adapter.aws.identity.AwsCredentialSource;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import pe.axiz.adapter.aws.transport.AsyncAwsTransportSettings;
import pe.axiz.adapter.aws.transport.CompletionPool;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;

class AwsCompletionExecutorTest {
  @Test
  void failedBindingRollsBackPartialMetersAndReleasesTheCredentialResource() {
    var closes = new AtomicInteger();
    try (var registry = new Registry()) {
      var collision = registry.counter("axiz.transport.queued", "transport", "broken");
      var source =
          AwsCredentialSource.owned(
              () -> AwsBasicCredentials.create("fixture", "fixture"), closes::incrementAndGet);
      assertThrows(
          IllegalArgumentException.class,
          () -> AwsCompletionMetrics.create("broken", 1, 1, registry, source));
      assertEquals(1, closes.get());
      assertEquals(java.util.List.of(collision), registry.getMeters());
      registry.remove(collision);
      try (var owner =
          AwsCompletionMetrics.create(
              "broken",
              1,
              1,
              registry,
              AwsCredentialSource.borrowed(
                  () -> AwsBasicCredentials.create("fixture", "fixture")))) {
        assertNotNull(registry.find("axiz.transport.queued").gauge());
      }
      assertTrue(registry.getMeters().isEmpty());
    }
  }

  private static final class Registry extends SimpleMeterRegistry implements AutoCloseable {}

  private record Pool(int active, int queued) implements CompletionPool {
    public int getActiveCount() {
      return active;
    }

    public int getQueueSize() {
      return queued;
    }
  }

  @Test
  void bindingsAggregateSignalsAndGaugesAndReleaseOnlyAfterTheLastOwner() {
    try (var registry = new Registry()) {
      var first = AwsCompletionMetrics.bind("test-aws-completion", new Pool(1, 1), registry);
      var second = AwsCompletionMetrics.bind("test-aws-completion", new Pool(0, 0), registry);
      try {
        first.rejected();
        first.completed(10);
        first.completed(20);
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
        assertEquals(
            2,
            registry
                .get("axiz.transport.latency")
                .tag("transport", "test-aws-completion")
                .timer()
                .count());
        first.close();
        assertNotNull(registry.find("axiz.transport.pending").gauge());
        first.close();
        second.close();
        assertTrue(registry.getMeters().isEmpty());
      } finally {
        first.close();
        second.close();
      }
    }
  }

  @Test
  void equalPoolNamesAreIsolatedByApplicationRegistryAndCleanupIsIdempotent() {
    try (var firstRegistry = new Registry();
        var secondRegistry = new Registry()) {
      var first = AwsCompletionMetrics.bind("same-pool", new Pool(0, 0), firstRegistry);
      var second = AwsCompletionMetrics.bind("same-pool", new Pool(0, 0), secondRegistry);
      try {
        first.completed(1);
        assertEquals(1, firstRegistry.get("axiz.transport.latency").timer().count());
        assertEquals(0, secondRegistry.get("axiz.transport.latency").timer().count());
        first.close();
        first.close();
        assertTrue(firstRegistry.getMeters().isEmpty());
        assertNotNull(secondRegistry.find("axiz.transport.pending").gauge());
      } finally {
        first.close();
        second.close();
      }
    }
  }

  @Test
  void applicationBindingDoesNotRequireAddingTheRegistryToTheGlobalComposite() {
    try (var registry = new Registry();
        var owner =
            new AsyncAwsClients(
                AwsCredentialSource.borrowed(
                    () -> AwsBasicCredentials.create("offline", "offline")),
                Clock.systemUTC(),
                Duration.ofDays(1),
                1,
                1,
                AsyncAwsTransportSettings.defaults())) {
      AwsCompletionMetrics.bind(owner, "application-only", registry);
      assertNotNull(
          registry.find("axiz.transport.pending").tag("transport", "application-only").gauge());
      assertNull(
          Metrics.globalRegistry
              .find("axiz.transport.pending")
              .tag("transport", "application-only")
              .gauge());
      AwsCompletionMetrics.bind(owner, "replacement", registry);
      assertNull(
          registry.find("axiz.transport.pending").tag("transport", "application-only").gauge());
      owner.close();
      owner.close();
      assertTrue(registry.getMeters().isEmpty());
      assertThrows(
          IllegalStateException.class, () -> AwsCompletionMetrics.bind(owner, "closed", registry));
      assertThrows(
          IllegalArgumentException.class, () -> AwsCompletionMetrics.create("", 1, 1, registry));
    }
  }
}
