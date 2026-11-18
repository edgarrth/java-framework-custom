package pe.axiz.core.saga;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.axiz.core.crypto.PayloadProtector;
import pe.axiz.core.resilience.Retry;
import pe.axiz.core.resilience.RetryPolicy;

/**
 * Runs sagas: executes steps in order (with retries for transient failures), persists state after
 * every step and, when a step fails, compensates the completed steps in reverse order. A saga
 * interrupted by a crash is resumed from its persisted state.
 *
 * <p>Guarantees: a failed step is only treated as "no effect" when the participant rejected it
 * definitely ({@link StepFailures#isDefiniteRejection}) or confirmed it through {@link
 * SagaStep#verify}; an uncertain outcome leaves the saga IN_DOUBT instead of compensating or
 * declaring a result. A step whose execution succeeded is always compensated if the saga aborts,
 * also when recording its result failed; listener failures never trigger or alter compensation; the
 * intent to compensate is persisted before anything is undone. Steps and compensations must be
 * idempotent, since a crash between running one and recording it repeats it on resume.
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
    return run(definition, contexts, state);
  }

  public <C> SagaResult<C> resume(
      SagaDefinition<C> definition, ContextCodec<C> codec, String sagaId) {
    SagaState state =
        store
            .find(sagaId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown saga " + sagaId));
    if (!state.sagaName().equals(definition.name())) {
      throw new IllegalArgumentException("Saga " + sagaId + " is not a " + definition.name());
    }
    return run(definition, new SagaContextStore<>(definition.name(), codec, protector), state);
  }

  private <C> SagaResult<C> run(
      SagaDefinition<C> definition, SagaContextStore<C> contexts, SagaState initial) {
    SagaState state = initial;
    C context = contexts.fromStored(state.sagaId(), state.context());
    List<SagaStep<C>> steps = definition.steps();
    if (state.status() == SagaStatus.IN_DOUBT) {
      int i = state.stepIndex();
      Resolution<C> r = resolve(definition, contexts, state, context, steps.get(i), i, null);
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
        try {
          context = new Retry(stepRetry).call(() -> step.execute(current));
        } catch (Exception e) {
          if (StepFailures.isDefiniteRejection(e)) {
            // the participant refused: step i has no effect, compensate the steps before it
            return abort(definition, contexts, state, context, step, i - 1, e);
          }
          // uncertain: the effect may exist, ask the participant before deciding anything
          Resolution<C> r = resolve(definition, contexts, state, context, step, i, e);
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
                      clock.instant()));
        } catch (SagaConcurrencyException e) {
          throw e; // another instance owns this saga
        } catch (RuntimeException e) {
          // step i ran but its result could not be recorded: it must be compensated as well
          return abort(definition, contexts, state, context, step, i, e);
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
                  clock.instant()));
      notifyListener(
          () -> listener.finished(definition.name(), initial.sagaId(), SagaStatus.COMPLETED, null));
      return new SagaResult<>(state.sagaId(), SagaStatus.COMPLETED, context, null);
    }
    if (state.status() == SagaStatus.COMPENSATING) {
      SagaResult<C> r = compensate(definition, state, context, null);
      notifyListener(
          () -> listener.finished(definition.name(), initial.sagaId(), r.status(), null));
      return r;
    }
    return new SagaResult<>(state.sagaId(), state.status(), context, null);
  }

  /**
   * Either a final result, or the state and context to continue with (step {@code i} applied). Two
   * types instead of nullable fields: each case carries exactly what it needs, never a null.
   */
  private sealed interface Resolution<C> permits Resolution.Done, Resolution.Continue {

    static <C> Resolution<C> done(SagaResult<C> result) {
      return new Done<>(result);
    }

    static <C> Resolution<C> continueWith(SagaState state, C context) {
      return new Continue<>(state, context);
    }

    /** The saga ended here. */
    record Done<C>(SagaResult<C> result) implements Resolution<C> {}

    /** The saga goes on from this state. */
    record Continue<C>(SagaState state, C context) implements Resolution<C> {}
  }

  /**
   * Step {@code i} failed with an uncertain outcome: verifies it with the participant. Applied: the
   * saga continues as if the step succeeded. Not applied: the previous steps are compensated.
   * Unknown: the saga is left IN_DOUBT (nothing compensated, nothing declared) for recovery.
   */
  private <C> Resolution<C> resolve(
      SagaDefinition<C> definition,
      SagaContextStore<C> contexts,
      SagaState state,
      C context,
      SagaStep<C> step,
      int i,
      @Nullable Exception cause) {
    StepCheck<C> check;
    try {
      check = step.verify(context);
    } catch (Exception e) {
      LOG.warn(
          "Saga {} {} could not verify step {}: {}",
          definition.name(),
          state.sagaId(),
          step.name(),
          e.getClass().getSimpleName());
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
                      clock.instant()));
        } catch (SagaConcurrencyException e) {
          throw e;
        } catch (RuntimeException e) {
          yield Resolution.done(abort(definition, contexts, state, next, step, i, e));
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
      case StepCheck.NotApplied<C> notApplied ->
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
                      : new SagaStepRejectedException("verified as not applied", null)));
      case StepCheck.Unknown<C> unknown -> {
        String error =
            cause != null
                ? truncate(step.name() + ": in doubt, " + cause.getClass().getSimpleName())
                : Objects.requireNonNullElse(state.error(), step.name() + ": in doubt");
        SagaState inDoubt =
            save(
                state.next(
                    SagaStatus.IN_DOUBT,
                    i,
                    contexts.toStored(state.sagaId(), context),
                    error,
                    clock.instant()));
        LOG.warn(
            "Saga {} {} IN_DOUBT at step {}: outcome unknown, nothing compensated",
            definition.name(),
            state.sagaId(),
            step.name());
        String stepName = step.name();
        notifyListener(() -> listener.inDoubt(definition.name(), state.sagaId(), stepName));
        yield Resolution.done(
            new SagaResult<>(inDoubt.sagaId(), SagaStatus.IN_DOUBT, context, step.name()));
      }
    };
  }

  /**
   * Records the intent to compensate and then compensates from {@code lastToCompensate} down to the
   * first step. The intent is persisted first: when even that write fails the exception propagates,
   * nothing is undone and the saga stays RUNNING, so recovery resumes it (steps must be
   * idempotent).
   */
  private <C> SagaResult<C> abort(
      SagaDefinition<C> definition,
      SagaContextStore<C> contexts,
      SagaState state,
      C context,
      SagaStep<C> failed,
      int lastToCompensate,
      Exception cause) {
    LOG.warn(
        "Saga {} {} failed at step {}: {}",
        definition.name(),
        state.sagaId(),
        failed.name(),
        cause.getClass().getSimpleName());
    SagaState compensating =
        save(
            state.next(
                SagaStatus.COMPENSATING,
                lastToCompensate,
                contexts.toStored(state.sagaId(), context),
                truncate(failed.name() + ": " + cause.getClass().getSimpleName()),
                clock.instant()));
    SagaResult<C> r = compensate(definition, compensating, context, failed.name());
    notifyListener(() -> listener.finished(definition.name(), state.sagaId(), r.status(), cause));
    return r;
  }

  private <C> SagaResult<C> compensate(
      SagaDefinition<C> definition, SagaState from, C context, @Nullable String failedStep) {
    SagaState state = from;
    for (int j = state.stepIndex(); j >= 0; j--) {
      SagaStep<C> step = definition.steps().get(j);
      try {
        new Retry(compensationRetry)
            .call(
                () -> {
                  step.compensate(context);
                  return null;
                });
      } catch (Exception e) {
        LOG.error(
            "Saga {} {} could not compensate step {}: manual intervention required; failureType={}",
            definition.name(),
            state.sagaId(),
            step.name(),
            e.getClass().getSimpleName());
        state =
            save(
                state.next(
                    SagaStatus.FAILED,
                    j,
                    state.context(),
                    truncate("compensation " + step.name() + ": " + e.getClass().getSimpleName()),
                    clock.instant()));
        return new SagaResult<>(state.sagaId(), SagaStatus.FAILED, context, failedStep);
      }
      // a failure to record progress propagates: resuming compensates step j again
      // (compensations must be idempotent), it is never reported as a failed compensation
      state =
          save(
              state.next(
                  SagaStatus.COMPENSATING, j - 1, state.context(), state.error(), clock.instant()));
      String stepName = step.name();
      String sagaId = state.sagaId();
      notifyListener(() -> listener.compensated(definition.name(), sagaId, stepName));
    }
    state =
        save(
            state.next(
                SagaStatus.COMPENSATED, -1, state.context(), state.error(), clock.instant()));
    return new SagaResult<>(state.sagaId(), SagaStatus.COMPENSATED, context, failedStep);
  }

  /** Listeners observe the saga; their failures never change its outcome. */
  private static void notifyListener(Runnable call) {
    try {
      call.run();
    } catch (RuntimeException e) {
      LOG.warn(
          "Saga listener failed; outcome unchanged; failureType={}", e.getClass().getSimpleName());
    }
  }

  private SagaState save(SagaState state) {
    store.update(state);
    return state;
  }

  private static String truncate(String s) {
    return s.length() > MAX_ERROR ? s.substring(0, MAX_ERROR) : s;
  }
}
