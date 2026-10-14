package pe.axiz.core.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.axiz.core.money.Money;

/**
 * Generic event metrics: seven instruments parameterized by the {@code event} tag. A new event
 * needs no code in the library; Grafana filters by {@code event}.
 *
 * <p>Every series of an instrument carries the same tag keys ({@code none} when a tag does not
 * apply) so Prometheus accepts them. Recording never throws: metrics must not break business
 * operations.
 */
public final class EventMetrics {

  private static final Logger LOG = LoggerFactory.getLogger(EventMetrics.class);

  private final MeterRegistry registry;
  private final MetricsSettings settings;
  private final CardinalityGuard guard;
  private final List<FailureClassifier> classifiers;
  private final List<String> customKeys;
  private final Map<Tags, AtomicLong> activeCounters = new ConcurrentHashMap<>();

  public EventMetrics(
      MeterRegistry registry, MetricsSettings settings, List<FailureClassifier> classifiers) {
    this.registry = Objects.requireNonNull(registry);
    this.settings = Objects.requireNonNull(settings);
    this.guard = new CardinalityGuard(settings);
    List<FailureClassifier> chain = new ArrayList<>(classifiers);
    chain.add(FailureClassifier.defaults());
    this.classifiers = List.copyOf(chain);
    this.customKeys = List.copyOf(new TreeSet<>(settings.customTags().keySet()));
  }

  public EventMetrics(MeterRegistry registry, MetricsSettings settings) {
    this(registry, settings, List.of());
  }

  /** Counts the occurrence and records amount, value and duration when present. */
  public void record(MetricEvent e) {
    safely(
        () -> {
          Tags base = baseTags(e, true);
          Counter.builder(MetricNames.COUNT)
              .description("Occurrences of business events")
              .tags(base)
              .register(registry)
              .increment();
          Money amount = e.amount();
          if (amount != null) {
            DistributionSummary.builder(MetricNames.AMOUNT)
                .description("Amounts of business events, in the currency of the currency tag")
                .tags(base.and(MetricTags.CURRENCY, amount.currencyCode()))
                .publishPercentileHistogram(settings.percentileHistograms())
                .register(registry)
                .record(amount.amount().doubleValue());
          }
          Double value = e.value();
          if (value != null) {
            DistributionSummary.builder(MetricNames.VALUE)
                .description("Non-monetary quantities of business events")
                .tags(base.and(MetricTags.UNIT, unitOf(e.event())))
                .publishPercentileHistogram(settings.percentileHistograms())
                .register(registry)
                .record(value);
          }
          Duration duration = e.duration();
          if (duration != null) {
            Timer.builder(MetricNames.DURATION)
                .description("Duration of business operations")
                .tags(base)
                .publishPercentileHistogram(settings.percentileHistograms())
                .register(registry)
                .record(duration.toNanos(), TimeUnit.NANOSECONDS);
          }
        });
  }

  public void count(String event) {
    record(MetricEvent.of(event));
  }

  public void count(String event, Consumer<MetricEvent> tags) {
    record(configure(event, tags));
  }

  public void value(String event, double value, Consumer<MetricEvent> tags) {
    record(configure(event, tags).value(value));
  }

  public void amount(String event, Money amount, Consumer<MetricEvent> tags) {
    record(configure(event, tags).amount(amount));
  }

  /** Starts timing an operation; call {@link Sample#stop(Outcome)} when it ends. */
  public Sample start(String event) {
    return new Sample(event, registry.config().clock().monotonicTime());
  }

  /**
   * Level that goes up and down (active sessions, pending requests).
   *
   * @param event event name
   * @param tags optional tags
   * @return the counter for these tags
   */
  public ActiveCounter active(String event, Consumer<MetricEvent> tags) {
    try {
      Tags t = levelTags(configure(event, tags));
      AtomicLong level =
          activeCounters.computeIfAbsent(
              t,
              key -> {
                AtomicLong holder = new AtomicLong();
                Gauge.builder(MetricNames.ACTIVE, holder, AtomicLong::doubleValue)
                    .description("Current level of business events")
                    .tags(key)
                    .strongReference(true)
                    .register(registry);
                return holder;
              });
      return new ActiveCounter(level);
    } catch (RuntimeException ex) {
      LOG.warn("Could not register active counter", ex);
      return new ActiveCounter(new AtomicLong());
    }
  }

  public ActiveCounter active(String event) {
    return active(event, t -> {});
  }

  /** Long running process in flight (reconciliations, batches, sagas). */
  public InflightTask inflight(String event, Consumer<MetricEvent> tags) {
    try {
      LongTaskTimer timer =
          LongTaskTimer.builder(MetricNames.INFLIGHT)
              .description("Business processes in progress")
              .tags(levelTags(configure(event, tags)))
              .register(registry);
      return new InflightTask(timer.start());
    } catch (RuntimeException ex) {
      LOG.warn("Could not start inflight timer", ex);
      return new InflightTask(null);
    }
  }

  /**
   * Observed value read on each scrape (queue size, current rate, available quota). The object is
   * held with a strong reference.
   *
   * @param event event name
   * @param target observed object
   * @param fn function reading the value
   * @param tags optional tags
   * @param <T> target type
   */
  public <T> void gauge(
      String event, T target, ToDoubleFunction<T> fn, Consumer<MetricEvent> tags) {
    safely(
        () ->
            Gauge.builder(MetricNames.GAUGE, target, fn)
                .description("Observed values of business state")
                .tags(levelTags(configure(event, tags)))
                .strongReference(true)
                .register(registry));
  }

  public <T> void gauge(String event, T target, ToDoubleFunction<T> fn) {
    gauge(event, target, fn, t -> {});
  }

  /**
   * Runs an operation counting it and timing it, deriving outcome and reason from the failure.
   * Arguments and results implementing {@link MetricSource} add amount, value and tags.
   *
   * @param event event name
   * @param category category or null
   * @param recordDuration whether to record the timer
   * @param sources arguments that may implement {@link MetricSource}
   * @param operation the operation
   * @return the operation result
   * @throws Throwable whatever the operation throws
   */
  public <T> T observe(
      String event,
      @Nullable String category,
      boolean recordDuration,
      List<?> sources,
      ThrowingSupplier<T> operation)
      throws Throwable {
    Observed observed = begin(event, category, recordDuration, sources);
    try {
      T result = operation.get();
      observed.succeed(result);
      return result;
    } catch (Throwable failure) {
      observed.fail(failure);
      throw failure;
    }
  }

  /**
   * Starts measuring an operation whose end is signalled later (reactive pipelines): call {@link
   * Observed#succeed} or {@link Observed#fail} exactly once; later calls are ignored.
   *
   * @param event event name
   * @param category category or null
   * @param recordDuration whether to record the timer
   * @param sources arguments that may implement {@link MetricSource}
   */
  public Observed begin(
      String event, @Nullable String category, boolean recordDuration, List<?> sources) {
    MetricEvent metric = MetricEvent.of(event).category(category);
    for (Object source : sources) {
      if (source instanceof MetricSource ms) {
        describeSafely(ms, metric);
      }
    }
    return new Observed(metric, recordDuration, registry.config().clock().monotonicTime());
  }

  /** An operation being measured, see {@link #begin}. */
  public final class Observed {
    private final MetricEvent metric;
    private final boolean recordDuration;
    private final long start;
    private final java.util.concurrent.atomic.AtomicBoolean done =
        new java.util.concurrent.atomic.AtomicBoolean();

    private Observed(MetricEvent metric, boolean recordDuration, long start) {
      this.metric = metric;
      this.recordDuration = recordDuration;
      this.start = start;
    }

    /** The operation succeeded; a result implementing {@link MetricSource} adds its data. */
    public void succeed(@Nullable Object result) {
      if (!done.compareAndSet(false, true)) {
        return;
      }
      if (result instanceof MetricSource ms) {
        describeSafely(ms, metric);
      }
      metric.outcome(Outcome.SUCCESS);
      finish();
    }

    /** The operation failed; outcome and reason come from the failure classifiers. */
    public void fail(Throwable failure) {
      if (!done.compareAndSet(false, true)) {
        return;
      }
      FailureClassifier.Classification c = classify(failure);
      metric.outcome(c.outcome()).reason(c.reason());
      finish();
    }

    private void finish() {
      if (recordDuration) {
        metric.duration(Duration.ofNanos(registry.config().clock().monotonicTime() - start));
      }
      record(metric);
    }
  }

  /** Classification used for a failure. */
  public FailureClassifier.Classification classify(Throwable failure) {
    for (FailureClassifier c : classifiers) {
      try {
        Optional<FailureClassifier.Classification> r = c.classify(failure);
        if (r.isPresent()) {
          return r.get();
        }
      } catch (RuntimeException ex) {
        LOG.debug("Failure classifier error", ex);
      }
    }
    return new FailureClassifier.Classification(Outcome.ERROR, "error");
  }

  private static void describeSafely(MetricSource source, MetricEvent metric) {
    try {
      source.describe(metric);
    } catch (RuntimeException ex) {
      LOG.debug("MetricSource failed", ex);
    }
  }

  private static MetricEvent configure(String event, Consumer<MetricEvent> tags) {
    MetricEvent e = MetricEvent.of(event);
    tags.accept(e);
    return e;
  }

  private Tags baseTags(MetricEvent e, boolean withOutcome) {
    String event = guard.event(e.event());
    List<Tag> tags = new ArrayList<>(16);
    tags.add(Tag.of(MetricTags.EVENT, event));
    tags.add(Tag.of(MetricTags.CATEGORY, guard.standard(MetricTags.CATEGORY, categoryOf(e))));
    if (withOutcome) {
      Outcome outcome = e.outcome() == null ? Outcome.SUCCESS : e.outcome();
      tags.add(Tag.of(MetricTags.OUTCOME, outcome.tagValue()));
      tags.add(Tag.of(MetricTags.REASON, guard.standard(MetricTags.REASON, e.reason())));
    }
    tags.add(Tag.of(MetricTags.CHANNEL, guard.channel(e.channel())));
    tags.add(Tag.of(MetricTags.PRODUCT, guard.standard(MetricTags.PRODUCT, e.product())));
    tags.add(Tag.of(MetricTags.STAGE, guard.standard(MetricTags.STAGE, e.stage())));
    tags.add(Tag.of(MetricTags.TENANT, guard.standard(MetricTags.TENANT, e.tenant())));
    Map<String, String> custom = e.tags();
    for (String key : customKeys) {
      String v = guard.custom(key, custom.get(key));
      tags.add(Tag.of(key, v == null ? MetricTags.NONE : v));
    }
    for (String key : custom.keySet()) {
      if (!settings.customTags().containsKey(key)) {
        guard.custom(key, custom.get(key)); // logs the undeclared tag once
      }
    }
    return Tags.of(tags);
  }

  private Tags levelTags(MetricEvent e) {
    return baseTags(e, false);
  }

  private @Nullable String categoryOf(MetricEvent e) {
    EventDefinition def = settings.events().get(e.event());
    if (def != null && def.category() != null) {
      return def.category();
    }
    return e.category();
  }

  private String unitOf(String event) {
    EventDefinition def = settings.events().get(event);
    return def != null && def.valueUnit() != null ? def.valueUnit() : MetricTags.NONE;
  }

  private static void safely(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException ex) {
      LOG.warn("Metric recording failed: {}", ex.toString());
    }
  }

  /** Supplier that may throw. */
  @FunctionalInterface
  public interface ThrowingSupplier<T> {
    T get() throws Throwable;
  }

  /** Timing of an operation started with {@link #start(String)}. */
  public final class Sample {
    private final String event;
    private final long startNanos;

    private Sample(String event, long startNanos) {
      this.event = event;
      this.startNanos = startNanos;
    }

    public void stop(Outcome outcome) {
      stop(outcome, t -> {});
    }

    /** Stops the sample, counting the event and recording its duration. */
    public void stop(Outcome outcome, Consumer<MetricEvent> tags) {
      long elapsed = registry.config().clock().monotonicTime() - startNanos;
      record(configure(event, tags).outcome(outcome).duration(Duration.ofNanos(elapsed)));
    }
  }

  /** Up-down level. */
  public static final class ActiveCounter {
    private final AtomicLong level;

    private ActiveCounter(AtomicLong level) {
      this.level = level;
    }

    public void increment() {
      level.incrementAndGet();
    }

    /** Decrements without going below zero. */
    public void decrement() {
      level.updateAndGet(v -> v > 0 ? v - 1 : 0);
    }

    public void add(long delta) {
      level.updateAndGet(v -> Math.max(0, v + delta));
    }

    public long get() {
      return level.get();
    }
  }

  /** A running long task; call {@link #stop()} exactly once. */
  public static final class InflightTask implements AutoCloseable {
    private final LongTaskTimer.@Nullable Sample sample;
    private boolean stopped;

    private InflightTask(LongTaskTimer.@Nullable Sample sample) {
      this.sample = sample;
    }

    public synchronized void stop() {
      if (!stopped && sample != null) {
        sample.stop();
      }
      stopped = true;
    }

    @Override
    public void close() {
      stop();
    }
  }
}
