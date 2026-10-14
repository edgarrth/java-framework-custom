package pe.axiz.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pe.axiz.core.money.Money;

class EventMetricsTest {

  private MockClock clock;
  private SimpleMeterRegistry registry;
  private EventMetrics metrics;
  private MetricsSettings settings;

  @BeforeEach
  void setUp() {
    clock = new MockClock();
    registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
    settings =
        MetricsSettings.defaults()
            .withCustomTags(
                TagPolicy.allowed("card-brand", Set.of("visa", "mastercard")),
                TagPolicy.bounded("segment", 2))
            .withEvents(
                new EventDefinition(
                    "payment",
                    "payment",
                    null,
                    List.of(Duration.ofMillis(100), Duration.ofMillis(500)),
                    List.of(100.0, 1000.0),
                    List.of()),
                new EventDefinition(
                    "file.load", "operation", "records", List.of(), List.of(), List.of(1000.0)));
    registry.config().meterFilter(new EventMetricsMeterFilter(settings, Set.of("service")));
    metrics = new EventMetrics(registry, settings);
  }

  @Test
  void recordCountsSumsAndTimesWithStandardTags() {
    metrics.record(
        MetricEvent.of("payment")
            .outcome(Outcome.SUCCESS)
            .channel("WEB")
            .product("credit-card")
            .amount(Money.of("150.00", "PEN"))
            .duration(Duration.ofMillis(120))
            .tag("card-brand", "visa"));
    metrics.record(
        MetricEvent.of("payment")
            .outcome(Outcome.SUCCESS)
            .channel("web")
            .product("credit-card")
            .amount(Money.of("50.00", "PEN"))
            .tag("card-brand", "visa"));

    Counter c =
        registry.get(MetricNames.COUNT).tag("event", "payment").tag("channel", "web").counter();
    assertEquals(2.0, c.count());
    assertEquals("payment", c.getId().getTag("category"));
    assertEquals("none", c.getId().getTag("reason"));
    assertEquals("none", c.getId().getTag("segment"));
    assertEquals("visa", c.getId().getTag("card-brand"));
    DistributionSummary amount = registry.get(MetricNames.AMOUNT).tag("currency", "PEN").summary();
    assertEquals(200.0, amount.totalAmount());
    assertEquals(150.0, amount.max());
    Timer timer = registry.get(MetricNames.DURATION).tag("event", "payment").timer();
    assertEquals(1, timer.count());
    assertEquals(120.0, timer.totalTime(TimeUnit.MILLISECONDS));
  }

  @Test
  void begunOperationsAreRecordedOnceWhenTheyFinish() {
    EventMetrics.Observed ok = metrics.begin("payment.query", "query", true, List.of());
    ok.succeed(null);
    ok.fail(new IllegalStateException("late")); // ignored
    metrics.begin("payment.query", "query", true, List.of()).fail(new IllegalStateException("x"));
    assertEquals(
        1.0,
        registry
            .get(MetricNames.COUNT)
            .tags("event", "payment.query", "outcome", "success")
            .counter()
            .count());
    assertEquals(
        1.0,
        registry
            .get(MetricNames.COUNT)
            .tags("event", "payment.query", "outcome", "error")
            .counter()
            .count());
  }

  @Test
  void anyNewEventWorksWithoutTouchingTheLibrary() {
    metrics.count(
        "login",
        t ->
            t.outcome(Outcome.REJECTED)
                .reason("invalid-otp")
                .channel("mobile")
                .category("security"));
    metrics.value("file.load", 12_480, t -> t.outcome(Outcome.SUCCESS));
    metrics.amount("refund", Money.of("10", "USD"), t -> {});
    assertEquals(
        1.0,
        registry
            .get(MetricNames.COUNT)
            .tags("event", "login", "outcome", "rejected", "reason", "invalid-otp")
            .counter()
            .count());
    DistributionSummary value = registry.get(MetricNames.VALUE).tag("event", "file.load").summary();
    assertEquals(12_480.0, value.totalAmount());
    assertEquals("records", value.getId().getTag("unit"));
    assertEquals("operation", value.getId().getTag("category"));
    assertEquals(
        10.0,
        registry
            .get(MetricNames.AMOUNT)
            .tags("event", "refund", "currency", "USD")
            .summary()
            .totalAmount());
  }

  @Test
  void allSeriesOfAnInstrumentShareTheSameTagKeys() {
    metrics.count("a");
    metrics.count(
        "b",
        t ->
            t.channel("web")
                .product("p")
                .stage("kyc")
                .tenant("t1")
                .reason("r")
                .tag("segment", "s1"));
    Set<Set<String>> keySets = new HashSet<>();
    for (Meter m : registry.find(MetricNames.COUNT).meters()) {
      Set<String> keys = new HashSet<>();
      for (Tag t : m.getId().getTagsAsIterable()) {
        keys.add(t.getKey());
      }
      keySets.add(keys);
    }
    assertEquals(1, keySets.size());
  }

  @Test
  void sampleMeasuresDurationWithOutcome() {
    EventMetrics.Sample sample = metrics.start("transfer.interbank");
    clock.add(Duration.ofMillis(250));
    sample.stop(Outcome.TIMEOUT, t -> t.reason("network"));
    Timer timer =
        registry
            .get(MetricNames.DURATION)
            .tags("event", "transfer.interbank", "outcome", "timeout")
            .timer();
    assertEquals(250.0, timer.totalTime(TimeUnit.MILLISECONDS));
    metrics.start("x").stop(Outcome.SUCCESS);
    assertEquals(1.0, registry.get(MetricNames.COUNT).tag("event", "x").counter().count());
  }

  @Test
  void activeInflightAndGauge() {
    EventMetrics.ActiveCounter sessions = metrics.active("session", t -> t.channel("web"));
    sessions.increment();
    sessions.increment();
    metrics.active("session", t -> t.channel("web")).decrement();
    sessions.add(-10);
    metrics.active("session").increment();
    Gauge g = registry.get(MetricNames.ACTIVE).tags("event", "session", "channel", "web").gauge();
    assertEquals(0.0, g.value());
    sessions.add(3);
    assertEquals(3.0, g.value());
    assertEquals(3, sessions.get());

    try (EventMetrics.InflightTask task =
        metrics.inflight("reconciliation", t -> t.category("operation"))) {
      clock.add(Duration.ofSeconds(5));
      LongTaskTimer ltt =
          registry.get(MetricNames.INFLIGHT).tag("event", "reconciliation").longTaskTimer();
      assertEquals(1, ltt.activeTasks());
      assertEquals(5.0, ltt.duration(TimeUnit.SECONDS));
    }
    assertEquals(0, registry.get(MetricNames.INFLIGHT).longTaskTimer().activeTasks());

    ArrayDeque<String> queue = new ArrayDeque<>(List.of("a", "b"));
    metrics.gauge("queue.pending", queue, ArrayDeque::size);
    queue.add("c");
    assertEquals(
        3.0, registry.get(MetricNames.GAUGE).tag("event", "queue.pending").gauge().value());
  }

  @Test
  void cardinalityIsBounded() {
    MetricsSettings small =
        new MetricsSettings(
            2, 1, Set.of("web"), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), false);
    EventMetrics m = new EventMetrics(registry, small);
    m.count("e1", t -> t.product("p1"));
    m.count("e2", t -> t.product("p2"));
    m.count("e3", t -> t.channel("smoke-signal"));
    m.count("Bad Name!");
    m.count("e1", t -> t.tag("undeclared", "x"));
    assertEquals(
        1.0,
        registry.get(MetricNames.COUNT).tags("event", "e2", "product", "other").counter().count());
    assertEquals(
        2.0,
        registry.get(MetricNames.COUNT).tags("event", "other").counters().stream()
            .mapToDouble(Counter::count)
            .sum());
    assertTrue(registry.find(MetricNames.COUNT).tagKeys("undeclared").counters().isEmpty());
    assertEquals(
        1.0,
        registry.get(MetricNames.COUNT).tags("event", "e1", "channel", "none").counters().stream()
            .filter(c -> "p1".equals(c.getId().getTag("product")))
            .mapToDouble(Counter::count)
            .sum());
  }

  @Test
  void disallowedCustomValuesBecomeOther() {
    metrics.count("payment", t -> t.tag("card-brand", "amex"));
    assertEquals(1.0, registry.get(MetricNames.COUNT).tag("card-brand", "other").counter().count());
  }

  @Test
  void meterFilterDeniesAdHocTagsAndAppliesCatalogBuckets() {
    registry.counter(MetricNames.COUNT, "event", "x", "customer-id", "123").increment();
    assertNull(registry.find(MetricNames.COUNT).tag("customer-id", "123").counter());
    registry.counter("other.metric", "customer-id", "123").increment();
    assertNotNull(registry.find("other.metric").counter());
    metrics.record(
        MetricEvent.of("payment").amount(Money.of("150", "PEN")).duration(Duration.ofMillis(120)));
    var snapshot =
        registry.get(MetricNames.AMOUNT).tag("event", "payment").summary().takeSnapshot();
    assertEquals(2, snapshot.histogramCounts().length);
  }

  static final class PaymentResult implements MetricSource {
    @Override
    public void describe(MetricEvent event) {
      event.amount(Money.of("99.90", "PEN")).product("debit").tag("card-brand", "mastercard");
    }
  }

  static final class Rejection extends RuntimeException {
    Rejection() {
      super("rejected");
    }
  }

  @Test
  void observeDerivesOutcomeAndUsesMetricSource() throws Throwable {
    EventMetrics m =
        new EventMetrics(
            registry,
            settings,
            List.of(
                f ->
                    f instanceof Rejection
                        ? Optional.of(
                            new FailureClassifier.Classification(Outcome.REJECTED, "AXZ-X-0001"))
                        : Optional.empty()));
    PaymentResult r = m.observe("payment", null, true, List.of(), PaymentResult::new);
    assertNotNull(r);
    assertEquals(
        99.90,
        registry.get(MetricNames.AMOUNT).tag("product", "debit").summary().totalAmount(),
        0.001);
    assertThrows(
        Rejection.class,
        () ->
            m.observe(
                "payment",
                null,
                true,
                List.of(),
                () -> {
                  throw new Rejection();
                }));
    assertEquals(
        1.0,
        registry
            .get(MetricNames.COUNT)
            .tags("outcome", "rejected", "reason", "AXZ-X-0001")
            .counter()
            .count());
    assertThrows(
        TimeoutException.class,
        () ->
            m.observe(
                "payment",
                "payment",
                false,
                List.of(),
                () -> {
                  throw new TimeoutException();
                }));
    assertEquals(1.0, registry.get(MetricNames.COUNT).tags("outcome", "timeout").counter().count());
    assertThrows(
        IllegalStateException.class,
        () ->
            m.observe(
                "payment",
                null,
                false,
                List.of(
                    (MetricSource)
                        e -> {
                          throw new IllegalArgumentException("broken source");
                        }),
                () -> {
                  throw new IllegalStateException();
                }));
    assertEquals(
        1.0,
        registry
            .get(MetricNames.COUNT)
            .tags("outcome", "error", "reason", "illegal-state-exception")
            .counter()
            .count());
  }
}
