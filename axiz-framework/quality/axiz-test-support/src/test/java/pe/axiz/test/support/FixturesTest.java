package pe.axiz.test.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.PublishException;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricEvent;
import pe.axiz.core.metrics.MetricsSettings;
import pe.axiz.core.metrics.Outcome;
import pe.axiz.core.money.Money;

class FixturesTest {

  @Test
  void mutableClock() {
    MutableClock c = MutableClock.at("2026-01-01T00:00:00Z");
    c.advance(Duration.ofHours(1));
    assertEquals(Instant.parse("2026-01-01T01:00:00Z"), c.instant());
    c.set(Instant.EPOCH);
    assertEquals(Instant.EPOCH, c.withZone(ZoneId.of("America/Lima")).instant());
    assertEquals(ZoneId.of("America/Lima"), c.withZone(ZoneId.of("America/Lima")).getZone());
  }

  @Test
  void recordingPublisher() {
    RecordingEventPublisher p = new RecordingEventPublisher();
    EventEnvelope e =
        new EventEnvelope(
            "1",
            "s",
            "t.v1",
            Instant.EPOCH,
            null,
            "application/json",
            "{}".getBytes(StandardCharsets.UTF_8),
            Map.of());
    p.publish("dest", e);
    assertEquals(1, p.eventsOfType("t.v1").size());
    p.failing(true);
    assertThrows(PublishException.class, () -> p.publish("dest", e));
    p.clear();
    assertEquals(0, p.published().size());
  }

  @Test
  void metricAssertions() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    EventMetrics metrics = new EventMetrics(registry, MetricsSettings.defaults());
    metrics.record(
        MetricEvent.of("payment").outcome(Outcome.SUCCESS).amount(Money.of("10", "PEN")));
    MetricAssertions.assertCount(registry, "payment", "success", 1);
    assertEquals(10.0, MetricAssertions.amount(registry, "payment", "PEN"));
    assertThrows(
        AssertionError.class, () -> MetricAssertions.assertCount(registry, "payment", "error", 1));
  }
}
