package pe.axiz.core.saga;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resumes sagas left unfinished by a crash or a redeploy: non-terminal sagas not updated for a
 * while are resumed from their persisted state (steps are idempotent, so repeating the last one is
 * safe). Call {@link #resumeStale} from a scheduled job guarded by a cluster lock.
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

  /** Makes sagas of this definition resumable. */
  public <C> SagaRecovery register(SagaDefinition<C> definition, ContextCodec<C> codec) {
    definitions.put(definition.name(), new Registration<>(definition, codec));
    return this;
  }

  /**
   * Resumes up to {@code max} sagas idle for longer than {@code idle}.
   *
   * @return how many sagas were resumed
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
        LOG.error(
            "Saga {} could not be resumed; failureType={}",
            s.sagaId(),
            e.getClass().getSimpleName());
      }
    }
    return resumed;
  }

  private <C> void resume(Registration<C> r, String sagaId) {
    orchestrator.resume(r.definition(), r.codec(), sagaId);
  }
}
