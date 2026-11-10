package pe.axiz.core.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Creates the framework spans at architecture points (use case, command, query, event handler,
 * outbox, saga step...). Each observation is a span with business attributes and, through the
 * Micrometer handlers, a timer with low cardinality tags.
 */
public final class ArchitectureObservation {

  private final ObservationRegistry registry;
  private final Function<Throwable, Boolean> rejectionClassifier;
  private final AttributeSanitizer sanitizer;

  public ArchitectureObservation(
      ObservationRegistry registry,
      Function<Throwable, Boolean> rejectionClassifier,
      AttributeSanitizer sanitizer) {
    this.registry = Objects.requireNonNull(registry);
    this.rejectionClassifier = Objects.requireNonNull(rejectionClassifier);
    this.sanitizer = Objects.requireNonNull(sanitizer);
  }

  /**
   * Runs the action inside an observation.
   *
   * @param name observation name, see {@link AxizObservations}
   * @param operation logical operation, e.g. {@code create-order}
   * @param lowCardinality extra tags (bounded values only)
   * @param highCardinality extra span-only attributes
   * @param action code to run
   * @return the action result
   */
  public <T> T observe(
      String name,
      String operation,
      Map<String, String> lowCardinality,
      Map<String, String> highCardinality,
      Supplier<T> action) {
    Observation observation =
        Observation.createNotStarted(name, registry)
            .contextualName(operation)
            .lowCardinalityKeyValue(AxizAttributes.OPERATION, operation)
            .lowCardinalityKeyValues(toKeyValues(lowCardinality))
            .highCardinalityKeyValues(toKeyValues(sanitizer.sanitize(highCardinality)));
    observation.start();
    try (Observation.Scope scope = observation.openScope()) {
      T result = action.get();
      observation.lowCardinalityKeyValue(AxizAttributes.OUTCOME, Outcomes.SUCCESS);
      return result;
    } catch (RuntimeException | Error e) {
      observation.lowCardinalityKeyValue(
          AxizAttributes.OUTCOME, Outcomes.of(e, rejectionClassifier));
      observation.error(e);
      throw e;
    } finally {
      observation.stop();
    }
  }

  public void run(
      String name, String operation, Map<String, String> lowCardinality, Runnable action) {
    observe(
        name,
        operation,
        lowCardinality,
        Map.of(),
        () -> {
          action.run();
          return null;
        });
  }

  /**
   * Starts an observation whose end is signalled later (reactive pipelines): call {@link
   * Started#succeed} or {@link Started#fail} exactly once; the observation is stopped then. The
   * caller propagates {@link Started#observation()} (e.g. in the Reactor context) so nested spans
   * become its children.
   *
   * @param name observation name, see {@link AxizObservations}
   * @param operation logical operation
   * @param lowCardinality extra tags (bounded values only)
   * @param highCardinality extra span-only attributes
   */
  public Started start(
      String name,
      String operation,
      Map<String, String> lowCardinality,
      Map<String, String> highCardinality) {
    Observation observation =
        Observation.createNotStarted(name, registry)
            .contextualName(operation)
            .lowCardinalityKeyValue(AxizAttributes.OPERATION, operation)
            .lowCardinalityKeyValues(toKeyValues(lowCardinality))
            .highCardinalityKeyValues(toKeyValues(sanitizer.sanitize(highCardinality)));
    observation.start();
    return new Started(observation);
  }

  /** An observation started by {@link #start}. */
  public final class Started {
    private final Observation observation;
    private final AtomicBoolean done = new AtomicBoolean();

    private Started(Observation observation) {
      this.observation = observation;
    }

    public Observation observation() {
      return observation;
    }

    public void succeed() {
      if (done.compareAndSet(false, true)) {
        observation.lowCardinalityKeyValue(AxizAttributes.OUTCOME, Outcomes.SUCCESS);
        observation.stop();
      }
    }

    public void fail(Throwable failure) {
      if (done.compareAndSet(false, true)) {
        observation.lowCardinalityKeyValue(
            AxizAttributes.OUTCOME, Outcomes.of(failure, rejectionClassifier));
        observation.error(failure);
        observation.stop();
      }
    }
  }

  /** Adds an event to the current observation, if any (shown as a span event). */
  public void event(String eventName, @Nullable String description) {
    Observation current = registry.getCurrentObservation();
    if (current != null) {
      current.event(Observation.Event.of(eventName, description == null ? eventName : description));
    }
  }

  private static KeyValues toKeyValues(Map<String, String> map) {
    return KeyValues.of(
        map.entrySet().stream().map(e -> KeyValue.of(e.getKey(), e.getValue())).toList());
  }
}
