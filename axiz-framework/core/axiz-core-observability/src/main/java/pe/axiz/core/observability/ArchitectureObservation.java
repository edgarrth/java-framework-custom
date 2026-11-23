package pe.axiz.core.observability;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
 * Crea los spans del framework en los puntos de la arquitectura (caso de uso, comando, consulta,
 * handler de eventos, outbox, paso de saga, etc.). Cada observación es un span con atributos de
 * negocio y, mediante los handlers de Micrometer, un timer con tags de baja cardinalidad.
 */
public final class ArchitectureObservation {

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "ObservationRegistry is an injected collaborator whose identity and lifecycle must "
              + "be shared, not copied.")
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
   * Ejecuta la acción dentro de una observación.
   *
   * @param name nombre de la observación, ver {@link AxizObservations}
   * @param operation operación lógica, por ejemplo {@code create-order}
   * @param lowCardinality tags adicionales (solo valores acotados)
   * @param highCardinality atributos adicionales solo para el span
   * @param action código a ejecutar
   * @return el resultado de la acción
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
   * Inicia una observación cuyo fin se señala más tarde (pipelines reactivos): se llama a {@link
   * Started#succeed} o a {@link Started#fail} exactamente una vez, y en ese momento se detiene la
   * observación. Quien llama propaga {@link Started#observation()} (por ejemplo, en el contexto de
   * Reactor) para que los spans anidados sean sus hijos.
   *
   * @param name nombre de la observación, ver {@link AxizObservations}
   * @param operation operación lógica
   * @param lowCardinality tags adicionales (solo valores acotados)
   * @param highCardinality atributos adicionales solo para el span
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

  /** Observación iniciada por {@link #start}. */
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

  /** Agrega un evento a la observación actual, si existe (se muestra como evento del span). */
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
