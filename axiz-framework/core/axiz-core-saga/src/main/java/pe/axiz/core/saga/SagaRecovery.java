package pe.axiz.core.saga;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.axiz.core.errors.Failures;

/**
 * Reanuda las sagas que quedaron sin terminar por una caída o un redespliegue: las sagas no
 * terminales sin actualizar durante un tiempo se reanudan desde su estado persistido (los pasos son
 * idempotentes, así que repetir el último es seguro). {@link #resumeStale} se llama desde un job
 * programado protegido por un lock de clúster.
 */
public final class SagaRecovery {

  private static final Logger LOG = LoggerFactory.getLogger(SagaRecovery.class);

  private record Registration<C>(SagaDefinition<C> definition, ContextCodec<C> codec) {}

  private final SagaStateStore store;
  private final SagaOrchestrator orchestrator;
  private final Clock clock;
  private final Map<String, Registration<?>> definitions = new ConcurrentHashMap<>();

  public SagaRecovery(SagaStateStore store, SagaOrchestrator orchestrator, Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.orchestrator = Objects.requireNonNull(orchestrator);
    this.clock = Objects.requireNonNull(clock);
  }

  /** Hace reanudables las sagas de esta definición. */
  public <C> SagaRecovery register(SagaDefinition<C> definition, ContextCodec<C> codec) {
    definitions.put(definition.name(), new Registration<>(definition, codec));
    return this;
  }

  /**
   * Reanuda hasta {@code max} sagas inactivas durante más de {@code idle}.
   *
   * @return cuántas sagas se reanudaron
   */
  public int resumeStale(Duration idle, int max) {
    int resumed = 0;
    for (SagaState s : store.findStale(clock.instant().minus(idle), max)) {
      Registration<?> r = definitions.get(s.sagaName());
      if (r == null) {
        LOG.warn("Saga {} of unknown definition {} left as is", s.sagaId(), s.sagaName());
        continue;
      }
      try {
        resume(r, s.sagaId());
        resumed++;
      } catch (SagaConcurrencyException e) {
        LOG.info("Saga {} is being resumed by another instance", s.sagaId());
      } catch (RuntimeException e) {
        LOG.error("Saga {} could not be resumed; failure={}", s.sagaId(), Failures.describe(e));
      }
    }
    return resumed;
  }

  private <C> void resume(Registration<C> r, String sagaId) {
    orchestrator.resume(r.definition(), r.codec(), sagaId);
  }
}
