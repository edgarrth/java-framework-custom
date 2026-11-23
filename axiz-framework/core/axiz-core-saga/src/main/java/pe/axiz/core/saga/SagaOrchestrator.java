package pe.axiz.core.saga;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.axiz.core.crypto.PayloadProtector;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.Failures;
import pe.axiz.core.lock.LockErrors;
import pe.axiz.core.lock.LockLease;
import pe.axiz.core.lock.LockObserver;
import pe.axiz.core.lock.LockTemplate;
import pe.axiz.core.resilience.Retry;
import pe.axiz.core.resilience.RetryPolicy;

/**
 * Ejecuta sagas: ejecuta los pasos en orden (con reintentos para fallos transitorios), persiste el
 * estado tras cada paso y, cuando un paso falla, compensa los pasos completados en orden inverso.
 * Una saga interrumpida por una caída se reanuda desde su estado persistido.
 *
 * <p>Garantías: un paso fallido solo se trata como "sin efecto" cuando el participante lo rechazó
 * de forma definitiva ({@link StepFailures#isDefiniteRejection}) o lo confirmó mediante {@link
 * SagaStep#verify}; un resultado incierto deja la saga IN_DOUBT en lugar de compensar o declarar un
 * resultado. Un paso cuya ejecución tuvo éxito siempre se compensa si la saga aborta, también
 * cuando falló el registro de su resultado; los fallos de los listeners nunca disparan ni alteran
 * la compensación; la intención de compensar se persiste antes de deshacer cualquier cosa. Los
 * pasos y las compensaciones deben ser idempotentes, ya que una caída entre ejecutar uno y
 * registrarlo lo repite al reanudar.
 */
public final class SagaOrchestrator {

  private static final Logger LOG = LoggerFactory.getLogger(SagaOrchestrator.class);
  private static final int MAX_ERROR = 1000;

  private final SagaStateStore store;
  private final RetryPolicy stepRetry;
  private final RetryPolicy compensationRetry;
  private final SagaListener listener;
  private final Clock clock;
  private final PayloadProtector protector;
  private final LockTemplate ownership;
  private final Duration executionLease;

  public SagaOrchestrator(
      SagaStateStore store,
      RetryPolicy stepRetry,
      RetryPolicy compensationRetry,
      SagaListener listener,
      Clock clock) {
    this(store, stepRetry, compensationRetry, listener, clock, PayloadProtector.NONE);
  }

  public SagaOrchestrator(
      SagaStateStore store,
      RetryPolicy stepRetry,
      RetryPolicy compensationRetry,
      SagaListener listener,
      Clock clock,
      PayloadProtector protector) {
    this(store, stepRetry, compensationRetry, listener, clock, protector, Duration.ofSeconds(30));
  }

  public SagaOrchestrator(
      SagaStateStore store,
      RetryPolicy stepRetry,
      RetryPolicy compensationRetry,
      SagaListener listener,
      Clock clock,
      PayloadProtector protector,
      Duration executionLease) {
    if (executionLease.compareTo(Duration.ofMillis(100)) < 0
        || executionLease.compareTo(Duration.ofMinutes(10)) > 0)
      throw new IllegalArgumentException("Saga execution lease must be between 100ms and 10m");
    this.executionLease = executionLease;
    this.ownership = new LockTemplate(store, Duration.ofMillis(10), LockObserver.NONE);
    this.protector = Objects.requireNonNull(protector);
    this.store = Objects.requireNonNull(store);
    this.stepRetry = Objects.requireNonNull(stepRetry);
    this.compensationRetry = Objects.requireNonNull(compensationRetry);
    this.listener = Objects.requireNonNull(listener);
    this.clock = Objects.requireNonNull(clock);
  }

  public <C> SagaResult<C> start(
      SagaDefinition<C> definition, ContextCodec<C> codec, String sagaId, C initial) {
    var contexts = new SagaContextStore<>(definition.name(), codec, protector);
    SagaState state =
        SagaState.builder()
            .sagaId(sagaId)
            .sagaName(definition.name())
            .status(SagaStatus.RUNNING)
            .stepIndex(0)
            .context(contexts.toStored(sagaId, initial))
            .error(null)
            .updatedAt(clock.instant())
            .version(0)
            .build();
    store.create(state);
    return execute(definition, contexts, sagaId);
  }

  public <C> SagaResult<C> resume(
      SagaDefinition<C> definition, ContextCodec<C> codec, String sagaId) {
    return execute(definition, new SagaContextStore<>(definition.name(), codec, protector), sagaId);
  }

  private <C> SagaResult<C> execute(
      SagaDefinition<C> definition, SagaContextStore<C> contexts, String sagaId) {
    try {
      return ownership.withLock(
          sagaId,
          executionLease,
          Duration.ZERO,
          lease -> {
            SagaState state =
                store.find(sagaId).orElseThrow(() -> new IllegalArgumentException("Unknown saga"));
            if (!state.sagaName().equals(definition.name()))
              throw new IllegalArgumentException("Saga definition mismatch");
            if (!state.status().terminal())
              state =
                  save(
                      state.next(
                          state.status(),
                          state.stepIndex(),
                          state.context(),
                          state.error(),
                          clock.instant()),
                      lease);
            return run(definition, contexts, state, lease);
          });
    } catch (BusinessException busy) {
      if (busy.code().equals(LockErrors.BUSY.code())) throw new SagaConcurrencyException(sagaId);
      throw busy;
    } catch (RuntimeException failure) {
      throw failure;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Saga execution interrupted", interrupted);
    } catch (Exception failure) {
      throw new IllegalStateException("Saga execution failed", failure);
    }
  }

  private <C> SagaResult<C> run(
      SagaDefinition<C> definition,
      SagaContextStore<C> contexts,
      SagaState initial,
      LockLease lease) {
    SagaState state = initial;
    C context = contexts.fromStored(state.sagaId(), state.context());
    List<SagaStep<C>> steps = definition.steps();
    if (state.status() == SagaStatus.IN_DOUBT) {
      int i = state.stepIndex();
      Resolution<C> r = resolve(definition, contexts, state, context, steps.get(i), i, null, lease);
      if (r instanceof Resolution.Done<C> done) {
        return done.result();
      }
      Resolution.Continue<C> next = (Resolution.Continue<C>) r;
      state = next.state();
      context = next.context();
    }
    if (state.status() == SagaStatus.RUNNING) {
      for (int i = state.stepIndex(); i < steps.size(); i++) {
        SagaStep<C> step = steps.get(i);
        C current = context;
        // La intención durable precede a la llamada. La recuperación nunca trata una llamada
        // interrumpida como ausente.
        state =
            save(
                state.next(
                    SagaStatus.IN_DOUBT,
                    i,
                    contexts.toStored(state.sagaId(), context),
                    null,
                    clock.instant()),
                lease);
        var execution = new SagaStepExecution(state.sagaId(), step.name(), state.version());
        var uncertainAttempt = new AtomicBoolean();
        try {
          context =
              new Retry(stepRetry)
                  .call(
                      () -> {
                        lease.ensureHeld();
                        try {
                          return step.execute(current, execution);
                        } catch (Exception failure) {
                          if (!StepFailures.isDefiniteRejection(failure))
                            uncertainAttempt.set(true);
                          throw failure;
                        }
                      });
        } catch (Exception e) {
          lease.ensureHeld();
          if (!uncertainAttempt.get() && StepFailures.isDefiniteRejection(e)) {
            // el participante rechazó: el paso i no tiene efecto, se compensan los pasos anteriores
            return abort(definition, contexts, state, context, step, i - 1, e, lease);
          }
          // incierto: el efecto puede existir, se consulta al participante antes de decidir nada
          Resolution<C> r = resolve(definition, contexts, state, context, step, i, e, lease);
          if (r instanceof Resolution.Done<C> done) {
            return done.result();
          }
          Resolution.Continue<C> next = (Resolution.Continue<C>) r;
          state = next.state();
          context = next.context();
          continue;
        }
        try {
          state =
              save(
                  state.next(
                      SagaStatus.RUNNING,
                      i + 1,
                      contexts.toStored(state.sagaId(), context),
                      null,
                      clock.instant()),
                  lease);
        } catch (SagaConcurrencyException e) {
          throw e; // otra instancia es dueña de esta saga
        } catch (RuntimeException e) {
          // el paso i se ejecutó pero su resultado no pudo registrarse: también debe compensarse
          return abort(definition, contexts, state, context, step, i, e, lease);
        }
        String stepName = step.name();
        notifyListener(() -> listener.stepCompleted(definition.name(), initial.sagaId(), stepName));
      }
      state =
          save(
              state.next(
                  SagaStatus.COMPLETED,
                  steps.size(),
                  contexts.toStored(state.sagaId(), context),
                  null,
                  clock.instant()),
              lease);
      notifyListener(
          () -> listener.finished(definition.name(), initial.sagaId(), SagaStatus.COMPLETED, null));
      return new SagaResult<>(state.sagaId(), SagaStatus.COMPLETED, context, null);
    }
    if (state.status() == SagaStatus.COMPENSATING) {
      SagaResult<C> r = compensate(definition, state, context, null, lease);
      notifyListener(
          () -> listener.finished(definition.name(), initial.sagaId(), r.status(), null));
      return r;
    }
    return new SagaResult<>(state.sagaId(), state.status(), context, null);
  }

  /**
   * Un resultado final, o el estado y el contexto con los que continuar (paso {@code i} aplicado).
   * Dos tipos en lugar de campos que admiten null: cada caso lleva exactamente lo que necesita,
   * nunca un null.
   */
  private sealed interface Resolution<C> permits Resolution.Done, Resolution.Continue {

    static <C> Resolution<C> done(SagaResult<C> result) {
      return new Done<>(result);
    }

    static <C> Resolution<C> continueWith(SagaState state, C context) {
      return new Continue<>(state, context);
    }

    /** La saga terminó aquí. */
    record Done<C>(SagaResult<C> result) implements Resolution<C> {}

    /** La saga continúa desde este estado. */
    record Continue<C>(SagaState state, C context) implements Resolution<C> {}
  }

  /**
   * El paso {@code i} falló con un resultado incierto: lo verifica con el participante. Aplicado:
   * la saga continúa como si el paso hubiera tenido éxito. Rechazado de forma terminal: se
   * compensan los pasos anteriores. La ausencia observada sigue siendo incierta. Desconocido: la
   * saga queda IN_DOUBT (nada compensado, nada declarado) para la recuperación.
   */
  private <C> Resolution<C> resolve(
      SagaDefinition<C> definition,
      SagaContextStore<C> contexts,
      SagaState state,
      C context,
      SagaStep<C> step,
      int i,
      @Nullable Exception cause,
      LockLease lease) {
    lease.ensureHeld();
    StepCheck<C> check;
    try {
      check =
          step.verify(context, new SagaStepExecution(state.sagaId(), step.name(), state.version()));
    } catch (Exception e) {
      LOG.warn(
          "Saga {} {} could not verify step {}: {}",
          definition.name(),
          state.sagaId(),
          step.name(),
          Failures.describe(e));
      check = StepCheck.unknown();
    }
    return switch (check) {
      case StepCheck.Applied<C> applied -> {
        C next = applied.context();
        SagaState saved;
        try {
          saved =
              save(
                  state.next(
                      SagaStatus.RUNNING,
                      i + 1,
                      contexts.toStored(state.sagaId(), next),
                      null,
                      clock.instant()),
                  lease);
        } catch (SagaConcurrencyException e) {
          throw e;
        } catch (RuntimeException e) {
          yield Resolution.done(abort(definition, contexts, state, next, step, i, e, lease));
        }
        LOG.info(
            "Saga {} {}: step {} was applied despite the failure",
            definition.name(),
            state.sagaId(),
            step.name());
        String stepName = step.name();
        notifyListener(() -> listener.stepCompleted(definition.name(), state.sagaId(), stepName));
        yield Resolution.continueWith(saved, next);
      }
      case StepCheck.Rejected<C> rejected ->
          Resolution.done(
              abort(
                  definition,
                  contexts,
                  state,
                  context,
                  step,
                  i - 1,
                  cause != null
                      ? cause
                      : new SagaStepRejectedException(
                          "participant guarantees terminal rejection", null),
                  lease));
      case StepCheck.NotApplied<C> notApplied ->
          unresolved(definition, contexts, state, context, step, i, cause, lease);
      case StepCheck.Unknown<C> unknown ->
          unresolved(definition, contexts, state, context, step, i, cause, lease);
    };
  }

  private <C> Resolution<C> unresolved(
      SagaDefinition<C> definition,
      SagaContextStore<C> contexts,
      SagaState state,
      C context,
      SagaStep<C> step,
      int i,
      @Nullable Exception cause,
      LockLease lease) {
    String error =
        cause != null
            ? truncate(step.name() + ": in doubt, " + cause.getClass().getSimpleName())
            : Objects.requireNonNullElse(state.error(), step.name() + ": in doubt");
    SagaState inDoubt =
        state.status() == SagaStatus.IN_DOUBT && cause == null
            ? state
            : save(
                state.next(
                    SagaStatus.IN_DOUBT,
                    i,
                    contexts.toStored(state.sagaId(), context),
                    error,
                    clock.instant()),
                lease);
    LOG.warn(
        "Saga {} {} IN_DOUBT at step {}: outcome unknown, nothing compensated; failure={}",
        definition.name(),
        state.sagaId(),
        step.name(),
        Failures.describe(cause));
    String stepName = step.name();
    notifyListener(() -> listener.inDoubt(definition.name(), state.sagaId(), stepName));
    return Resolution.done(
        new SagaResult<>(inDoubt.sagaId(), SagaStatus.IN_DOUBT, context, step.name()));
  }

  /**
   * Registra la intención de compensar y luego compensa desde {@code lastToCompensate} hasta el
   * primer paso. La intención se persiste primero: cuando incluso esa escritura falla, la excepción
   * se propaga, no se deshace nada y la saga queda IN_DOUBT, de modo que la recuperación verifica
   * el efecto pendiente.
   */
  private <C> SagaResult<C> abort(
      SagaDefinition<C> definition,
      SagaContextStore<C> contexts,
      SagaState state,
      C context,
      SagaStep<C> failed,
      int lastToCompensate,
      Exception cause,
      LockLease lease) {
    lease.ensureHeld();
    LOG.warn(
        "Saga {} {} failed at step {}: {}",
        definition.name(),
        state.sagaId(),
        failed.name(),
        Failures.describe(cause));
    SagaState compensating =
        save(
            state.next(
                SagaStatus.COMPENSATING,
                lastToCompensate,
                contexts.toStored(state.sagaId(), context),
                truncate(failed.name() + ": " + cause.getClass().getSimpleName()),
                clock.instant()),
            lease);
    SagaResult<C> r = compensate(definition, compensating, context, failed.name(), lease);
    notifyListener(() -> listener.finished(definition.name(), state.sagaId(), r.status(), cause));
    return r;
  }

  private <C> SagaResult<C> compensate(
      SagaDefinition<C> definition,
      SagaState from,
      C context,
      @Nullable String failedStep,
      LockLease lease) {
    SagaState state = from;
    for (int j = state.stepIndex(); j >= 0; j--) {
      SagaStep<C> step = definition.steps().get(j);
      state =
          save(
              state.next(
                  SagaStatus.COMPENSATING, j, state.context(), state.error(), clock.instant()),
              lease);
      var execution = new SagaStepExecution(state.sagaId(), step.name(), state.version());
      try {
        new Retry(compensationRetry)
            .call(
                () -> {
                  lease.ensureHeld();
                  step.compensate(context, execution);
                  return null;
                });
      } catch (Exception e) {
        lease.ensureHeld();
        LOG.error(
            "Saga {} {} could not compensate step {}: manual intervention required; failure={}",
            definition.name(),
            state.sagaId(),
            step.name(),
            Failures.describe(e));
        state =
            save(
                state.next(
                    SagaStatus.FAILED,
                    j,
                    state.context(),
                    truncate("compensation " + step.name() + ": " + e.getClass().getSimpleName()),
                    clock.instant()),
                lease);
        return new SagaResult<>(state.sagaId(), SagaStatus.FAILED, context, failedStep);
      }
      // un fallo al registrar el progreso se propaga: al reanudar se compensa de nuevo el paso j
      // (las compensaciones deben ser idempotentes); nunca se informa como compensación fallida
      state =
          save(
              state.next(
                  SagaStatus.COMPENSATING, j - 1, state.context(), state.error(), clock.instant()),
              lease);
      String stepName = step.name();
      String sagaId = state.sagaId();
      notifyListener(() -> listener.compensated(definition.name(), sagaId, stepName));
    }
    state =
        save(
            state.next(SagaStatus.COMPENSATED, -1, state.context(), state.error(), clock.instant()),
            lease);
    return new SagaResult<>(state.sagaId(), SagaStatus.COMPENSATED, context, failedStep);
  }

  /** Los listeners observan la saga; sus fallos nunca cambian su resultado. */
  private static void notifyListener(Runnable call) {
    try {
      call.run();
    } catch (RuntimeException e) {
      LOG.warn("Saga listener failed; outcome unchanged; failure={}", Failures.describe(e));
    }
  }

  private SagaState save(SagaState state, LockLease lease) {
    lease.ensureHeld();
    store.update(state, lease.token());
    return state;
  }

  private static String truncate(String s) {
    return s.length() > MAX_ERROR ? s.substring(0, MAX_ERROR) : s;
  }
}
