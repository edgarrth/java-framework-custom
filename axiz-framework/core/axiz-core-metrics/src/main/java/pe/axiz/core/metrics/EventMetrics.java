package pe.axiz.core.metrics;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.axiz.core.money.Money;

/**
 * Métricas de eventos genéricas: siete instrumentos parametrizados por el tag {@code event}. Un
 * evento nuevo no necesita código en la librería; Grafana filtra por {@code event}.
 *
 * <p>Cada serie de un instrumento lleva las mismas claves de tag ({@code none} cuando un tag no
 * aplica) para que Prometheus las acepte. El registro nunca lanza excepciones: las métricas no
 * deben romper las operaciones de negocio.
 */
public final class EventMetrics {

  private static final Logger LOG = LoggerFactory.getLogger(EventMetrics.class);

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "MeterRegistry is an injected collaborator whose identity and lifecycle must be "
              + "shared, not copied.")
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

  /** Cuenta la ocurrencia y registra el monto, el valor y la duración cuando están presentes. */
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

  /** Empieza a medir una operación; se llama a {@link Sample#stop(Outcome)} cuando termina. */
  public Sample start(String event) {
    return new Sample(event, registry.config().clock().monotonicTime());
  }

  /**
   * Nivel que sube y baja (sesiones activas, peticiones pendientes).
   *
   * @param event nombre del evento
   * @param tags tags opcionales
   * @return el contador para estos tags
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
      LOG.warn("Could not register active counter; failureType={}", ex.getClass().getSimpleName());
      return new ActiveCounter(new AtomicLong());
    }
  }

  public ActiveCounter active(String event) {
    return active(event, t -> {});
  }

  /** Proceso de larga duración en curso (reconciliaciones, lotes, sagas). */
  public InflightTask inflight(String event, Consumer<MetricEvent> tags) {
    try {
      LongTaskTimer timer =
          LongTaskTimer.builder(MetricNames.INFLIGHT)
              .description("Business processes in progress")
              .tags(levelTags(configure(event, tags)))
              .register(registry);
      return new InflightTask(timer.start());
    } catch (RuntimeException ex) {
      LOG.warn("Could not start inflight timer; failureType={}", ex.getClass().getSimpleName());
      return new InflightTask(null);
    }
  }

  /**
   * Valor observado que se lee en cada scrape (tamaño de cola, tasa actual, cuota disponible). El
   * objeto se mantiene con una referencia fuerte.
   *
   * @param event nombre del evento
   * @param target objeto observado
   * @param fn función que lee el valor
   * @param tags tags opcionales
   * @param <T> tipo del objetivo
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
   * Ejecuta una operación contándola y midiendo su duración, y deriva el resultado y el motivo a
   * partir del fallo. Los argumentos y resultados que implementan {@link MetricSource} agregan
   * monto, valor y tags.
   *
   * @param event nombre del evento
   * @param category categoría o null
   * @param recordDuration indica si se registra el timer
   * @param sources argumentos que pueden implementar {@link MetricSource}
   * @param operation la operación
   * @return el resultado de la operación
   * @throws Throwable lo que lance la operación
   */
  public <T extends @Nullable Object> T observe(
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
   * Empieza a medir una operación cuyo fin se señala más tarde (pipelines reactivos): se llama a
   * {@link Observed#succeed} o a {@link Observed#fail} exactamente una vez; las llamadas
   * posteriores se ignoran.
   *
   * @param event nombre del evento
   * @param category categoría o null
   * @param recordDuration indica si se registra el timer
   * @param sources argumentos que pueden implementar {@link MetricSource}
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

  /** Operación en medición, ver {@link #begin}. */
  public final class Observed {
    private final MetricEvent metric;
    private final boolean recordDuration;
    private final long start;
    private final AtomicBoolean done = new AtomicBoolean();

    private Observed(MetricEvent metric, boolean recordDuration, long start) {
      this.metric = metric;
      this.recordDuration = recordDuration;
      this.start = start;
    }

    /**
     * La operación tuvo éxito; un resultado que implementa {@link MetricSource} agrega sus datos.
     */
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

    /** La operación falló; el resultado y el motivo provienen de los clasificadores de fallos. */
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

  /** Clasificación usada para un fallo. */
  public FailureClassifier.Classification classify(Throwable failure) {
    for (FailureClassifier c : classifiers) {
      try {
        Optional<FailureClassifier.Classification> r = c.classify(failure);
        if (r.isPresent()) {
          return r.get();
        }
      } catch (RuntimeException ex) {
        LOG.debug("Failure classifier error; failureType={}", ex.getClass().getSimpleName());
      }
    }
    return new FailureClassifier.Classification(Outcome.ERROR, "error");
  }

  private static void describeSafely(MetricSource source, MetricEvent metric) {
    try {
      source.describe(metric);
    } catch (RuntimeException ex) {
      LOG.debug("MetricSource failed; failureType={}", ex.getClass().getSimpleName());
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
      Outcome reported = e.outcome();
      Outcome outcome = reported == null ? Outcome.SUCCESS : reported;
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
    for (Map.Entry<String, String> tag : custom.entrySet()) {
      if (!settings.customTags().containsKey(tag.getKey())) {
        guard.custom(tag.getKey(), tag.getValue()); // registra una vez el tag no declarado
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
      LOG.warn("Metric recording failed: {}", ex.getClass().getSimpleName());
    }
  }

  /** Supplier que puede lanzar excepciones. */
  @FunctionalInterface
  public interface ThrowingSupplier<T extends @Nullable Object> {
    T get() throws Throwable;
  }

  /** Medición de una operación iniciada con {@link #start(String)}. */
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

    /** Detiene la muestra, contando el evento y registrando su duración. */
    public void stop(Outcome outcome, Consumer<MetricEvent> tags) {
      long elapsed = registry.config().clock().monotonicTime() - startNanos;
      record(configure(event, tags).outcome(outcome).duration(Duration.ofNanos(elapsed)));
    }
  }

  /** Nivel que sube y baja. */
  public static final class ActiveCounter {
    private final AtomicLong level;

    private ActiveCounter(AtomicLong level) {
      this.level = level;
    }

    public void increment() {
      level.incrementAndGet();
    }

    /** Decrementa sin bajar de cero. */
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

  /** Tarea larga en ejecución; se llama a {@link #stop()} exactamente una vez. */
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
