package pe.axiz.adapter.saga.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorDefinition;
import pe.axiz.core.lock.LockHandle;
import pe.axiz.core.resilience.Backoff;
import pe.axiz.core.resilience.RetryPolicy;
import pe.axiz.core.saga.ContextCodec;
import pe.axiz.core.saga.InMemorySagaStateStore;
import pe.axiz.core.saga.SagaConcurrencyException;
import pe.axiz.core.saga.SagaDefinition;
import pe.axiz.core.saga.SagaListener;
import pe.axiz.core.saga.SagaOrchestrator;
import pe.axiz.core.saga.SagaRecovery;
import pe.axiz.core.saga.SagaResult;
import pe.axiz.core.saga.SagaState;
import pe.axiz.core.saga.SagaStateStore;
import pe.axiz.core.saga.SagaStatus;
import pe.axiz.core.saga.SagaStep;
import pe.axiz.core.saga.SagaStepRejectedException;
import pe.axiz.core.saga.StepCheck;
import pe.axiz.core.saga.StepFailures;
import pe.axiz.kit.jdbc.JdbcTransactions;

class SagaTest {

  /** Contexto: lista de acciones completadas, serializada como texto separado por comas. */
  static final class Codec implements ContextCodec<List<String>> {
    @Override
    public String encode(List<String> c) {
      return String.join(",", c);
    }

    @Override
    public List<String> decode(String text) {
      return text.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(text.split(",")));
    }
  }

  static class Step implements SagaStep<List<String>> {
    final String name;
    final List<String> log;
    int failExecute;
    boolean failCompensate;
    final AtomicInteger attempts = new AtomicInteger();

    Step(String name, List<String> log) {
      this.name = name;
      this.log = log;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public List<String> execute(List<String> context) throws Exception {
      attempts.incrementAndGet();
      if (failExecute > 0) {
        failExecute--;
        throw failExecute >= 100
            ? new SagaStepRejectedException("permanent", null)
            : new SocketTimeoutException("transient");
      }
      log.add("do:" + name);
      List<String> next = new ArrayList<>(context);
      next.add(name);
      return next;
    }

    StepCheck<List<String>> verification = StepCheck.unknown();
    final AtomicInteger verifications = new AtomicInteger();

    @Override
    public StepCheck<List<String>> verify(List<String> context) {
      verifications.incrementAndGet();
      return verification;
    }

    @Override
    public void compensate(List<String> context) {
      if (failCompensate) {
        throw new IllegalStateException("cannot undo");
      }
      log.add("undo:" + name);
    }
  }

  private static SagaOrchestrator orchestrator(SagaStateStore store) {
    return new SagaOrchestrator(
        store,
        RetryPolicy.transientFailures(3, Backoff.fixed(Duration.ZERO)),
        RetryPolicy.transientFailures(2, Backoff.fixed(Duration.ZERO)),
        SagaListener.none(),
        Clock.systemUTC());
  }

  private static SagaStateStore jdbcStore() throws Exception {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:saga-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement();
        InputStream in = SagaTest.class.getResourceAsStream("/axiz/sql/saga-h2.sql")) {
      for (String sql : new String(in.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
        if (!sql.replaceAll("--.*", "").isBlank()) {
          s.execute(sql);
        }
      }
    }
    return new JdbcSagaStateStore(new JdbcTransactions(ds), "axiz_saga");
  }

  private static SagaStateStore store(String kind) throws Exception {
    return kind.equals("jdbc") ? jdbcStore() : new InMemorySagaStateStore();
  }

  @ParameterizedTest
  @ValueSource(strings = {"memory", "jdbc"})
  void completesAllStepsWithTransientRetries(String kind) throws Exception {
    List<String> log = new ArrayList<>();
    Step reserve = new Step("reserve", log);
    Step send = new Step("send", log);
    send.failExecute = 2;
    SagaStateStore store = store(kind);
    SagaResult<List<String>> r =
        orchestrator(store)
            .start(
                new SagaDefinition<>("payment", List.of(reserve, send)),
                new Codec(),
                "s-1",
                new ArrayList<>());
    assertTrue(r.completed());
    assertEquals(List.of("reserve", "send"), r.context());
    assertEquals(3, send.attempts.get());
    assertEquals(SagaStatus.COMPLETED, store.find("s-1").orElseThrow().status());
  }

  @ParameterizedTest
  @ValueSource(strings = {"memory", "jdbc"})
  void compensatesInReverseOrder(String kind) throws Exception {
    List<String> log = new ArrayList<>();
    Step a = new Step("reserve", log);
    Step b = new Step("debit", log);
    Step c = new Step("send", log);
    c.failExecute = 101;
    SagaStateStore store = store(kind);
    SagaResult<List<String>> r =
        orchestrator(store)
            .start(
                new SagaDefinition<>("payment", List.of(a, b, c)),
                new Codec(),
                "s-2",
                new ArrayList<>());
    assertEquals(SagaStatus.COMPENSATED, r.status());
    assertEquals("send", r.failedStep());
    assertEquals(List.of("do:reserve", "do:debit", "undo:debit", "undo:reserve"), log);
    assertEquals(1, c.attempts.get(), "permanent failures are not retried");
    assertTrue(store.find("s-2").orElseThrow().error().contains("send"));
  }

  @Test
  void failedCompensationNeedsIntervention() throws Exception {
    List<String> log = new ArrayList<>();
    Step a = new Step("reserve", log);
    a.failCompensate = true;
    Step b = new Step("send", log);
    b.failExecute = 101;
    SagaStateStore store = new InMemorySagaStateStore();
    List<SagaStatus> finished = new ArrayList<>();
    SagaOrchestrator o =
        new SagaOrchestrator(
            store,
            RetryPolicy.none(),
            RetryPolicy.none(),
            new SagaListener() {
              @Override
              public void finished(
                  String sagaName, String sagaId, SagaStatus status, Throwable cause) {
                finished.add(status);
              }
            },
            Clock.systemUTC());
    assertEquals(
        SagaStatus.FAILED,
        o.start(new SagaDefinition<>("p", List.of(a, b)), new Codec(), "s-3", new ArrayList<>())
            .status());
    assertEquals(List.of(SagaStatus.FAILED), finished);
  }

  @Test
  void resumesAfterCrash() throws Exception {
    List<String> log = new ArrayList<>();
    Step a = new Step("reserve", log);
    Step b = new Step("send", log);
    SagaStateStore store = jdbcStore();
    SagaDefinition<List<String>> def = new SagaDefinition<>("payment", List.of(a, b));
    // Se simula una caída después de persistir de forma durable el paso 1.
    store.create(
        SagaState.builder()
            .sagaId("s-4")
            .sagaName("payment")
            .status(SagaStatus.RUNNING)
            .stepIndex(1)
            .context("reserve")
            .error(null)
            .updatedAt(Instant.EPOCH)
            .version(1)
            .build());
    assertEquals(1, store.findStale(Instant.now(), 10).size());
    SagaResult<List<String>> r = orchestrator(store).resume(def, new Codec(), "s-4");
    assertTrue(r.completed());
    assertEquals(List.of("do:send"), log, "completed steps are not executed again");
    assertEquals(List.of("reserve", "send"), r.context());
    assertTrue(store.findStale(Instant.now(), 10).isEmpty());
    assertEquals(
        SagaStatus.COMPLETED, orchestrator(store).resume(def, new Codec(), "s-4").status());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            orchestrator(store)
                .resume(new SagaDefinition<>("other", List.of(a)), new Codec(), "s-4"));
    assertThrows(
        SagaConcurrencyException.class,
        () ->
            store.update(
                SagaState.builder()
                    .sagaId("s-4")
                    .sagaName("payment")
                    .status(SagaStatus.RUNNING)
                    .stepIndex(1)
                    .context("")
                    .error(null)
                    .updatedAt(Instant.EPOCH)
                    .version(1)
                    .build(),
                "non-owner"));
    assertThrows(
        IllegalArgumentException.class, () -> new SagaDefinition<List<String>>("x", List.of()));
  }

  @Test
  void recoveryResumesStaleSagasOfKnownDefinitions() throws Exception {
    List<String> log = new ArrayList<>();
    SagaStateStore store = new InMemorySagaStateStore();
    SagaDefinition<List<String>> def =
        new SagaDefinition<>("payment", List.of(new Step("reserve", log), new Step("send", log)));
    store.create(
        SagaState.builder()
            .sagaId("s-5")
            .sagaName("payment")
            .status(SagaStatus.RUNNING)
            .stepIndex(1)
            .context("reserve")
            .error(null)
            .updatedAt(Instant.EPOCH)
            .version(0)
            .build());
    store.create(
        SagaState.builder()
            .sagaId("s-6")
            .sagaName("unknown")
            .status(SagaStatus.RUNNING)
            .stepIndex(0)
            .context("")
            .error(null)
            .updatedAt(Instant.EPOCH)
            .version(0)
            .build());

    SagaRecovery recovery =
        new SagaRecovery(store, orchestrator(store), Clock.systemUTC()).register(def, new Codec());

    assertEquals(1, recovery.resumeStale(Duration.ofMinutes(1), 10));
    assertEquals(List.of("do:send"), log);
    assertEquals(SagaStatus.COMPLETED, store.find("s-5").orElseThrow().status());
    assertEquals(SagaStatus.RUNNING, store.find("s-6").orElseThrow().status());
    assertEquals(0, recovery.resumeStale(Duration.ofMinutes(1), 10), "terminal sagas stay");
  }

  /**
   * Almacén cuya n-ésima actualización falla una vez (base de datos no disponible en ese momento).
   */
  static final class FlakyStore implements SagaStateStore {
    final InMemorySagaStateStore delegate = new InMemorySagaStateStore();
    Predicate<SagaState> failWhen = state -> false;
    boolean failed;

    @Override
    public void create(SagaState state) {
      delegate.create(state);
    }

    @Override
    public Optional<LockHandle> tryAcquire(String resource, Duration lease) {
      return delegate.tryAcquire(resource, lease);
    }

    @Override
    public void update(SagaState state, String owner) {
      if (!failed && failWhen.test(state)) {
        failed = true;
        throw new IllegalStateException("database unavailable");
      }
      delegate.update(state, owner);
    }

    @Override
    public Optional<SagaState> find(String sagaId) {
      return delegate.find(sagaId);
    }

    @Override
    public List<SagaState> findStale(Instant before, int limit) {
      return delegate.findStale(before, limit);
    }
  }

  /**
   * Regresión: un listener que lanza una excepción después de un paso no debe compensar ese paso.
   */
  @Test
  void aFailingListenerDoesNotCompensateACompletedStep() {
    List<String> log = new ArrayList<>();
    Step a = new Step("reserve", log);
    Step b = new Step("send", log);
    SagaStateStore store = new InMemorySagaStateStore();
    SagaOrchestrator o =
        new SagaOrchestrator(
            store,
            RetryPolicy.none(),
            RetryPolicy.none(),
            new SagaListener() {
              @Override
              public void stepCompleted(String sagaName, String sagaId, String step) {
                throw new IllegalStateException("metrics backend down");
              }

              @Override
              public void finished(
                  String sagaName, String sagaId, SagaStatus status, Throwable cause) {
                throw new IllegalStateException("metrics backend down");
              }
            },
            Clock.systemUTC());
    SagaResult<List<String>> r =
        o.start(new SagaDefinition<>("p", List.of(a, b)), new Codec(), "s-7", new ArrayList<>());
    assertEquals(SagaStatus.COMPLETED, r.status());
    assertEquals(List.of("do:reserve", "do:send"), log, "nothing was undone");
    assertEquals(SagaStatus.COMPLETED, store.find("s-7").orElseThrow().status());
  }

  /**
   * Regresión: el paso "debit" se ejecutó pero falló el registro de su resultado. La saga aborta y
   * "debit" también se compensa, no solo los pasos anteriores.
   */
  @Test
  void aStepThatRanButCouldNotBeRecordedIsCompensated() {
    List<String> log = new ArrayList<>();
    Step a = new Step("reserve", log);
    Step b = new Step("debit", log);
    Step c = new Step("send", log);
    FlakyStore store = new FlakyStore();
    store.failWhen = s -> s.status() == SagaStatus.RUNNING && s.stepIndex() == 2;
    SagaResult<List<String>> r =
        orchestrator(store)
            .start(
                new SagaDefinition<>("payment", List.of(a, b, c)),
                new Codec(),
                "s-8",
                new ArrayList<>());
    assertEquals(SagaStatus.COMPENSATED, r.status());
    assertEquals("debit", r.failedStep());
    assertEquals(List.of("do:reserve", "do:debit", "undo:debit", "undo:reserve"), log);
    assertEquals(SagaStatus.COMPENSATED, store.find("s-8").orElseThrow().status());
  }

  /**
   * Un fallo al registrar el progreso de la compensación no se reporta como compensación fallida.
   */
  @Test
  void aStoreFailureDuringCompensationPropagatesAndResumeFinishesIt() {
    List<String> log = new ArrayList<>();
    Step a = new Step("reserve", log);
    Step b = new Step("send", log);
    b.failExecute = 101;
    FlakyStore store = new FlakyStore();
    // Se falla específicamente al registrar la compensación completada.
    store.failWhen = s -> s.status() == SagaStatus.COMPENSATING && s.stepIndex() == -1;
    SagaDefinition<List<String>> def = new SagaDefinition<>("payment", List.of(a, b));
    assertThrows(
        IllegalStateException.class,
        () -> orchestrator(store).start(def, new Codec(), "s-9", new ArrayList<>()));
    SagaState pending = store.find("s-9").orElseThrow();
    assertEquals(SagaStatus.COMPENSATING, pending.status(), "never marked FAILED");
    assertEquals(0, pending.stepIndex());
    assertEquals(
        SagaStatus.COMPENSATED, orchestrator(store).resume(def, new Codec(), "s-9").status());
    assertEquals(List.of("do:reserve", "undo:reserve", "undo:reserve"), log);
  }

  /** Cuando ni siquiera puede registrarse la intención de compensar, no se deshace nada. */
  @Test
  void nothingIsUndoneUnlessTheIntentToCompensateWasRecorded() {
    List<String> log = new ArrayList<>();
    Step a = new Step("reserve", log);
    Step b = new Step("send", log);
    b.failExecute = 101;
    FlakyStore store = new FlakyStore();
    store.failWhen = s -> s.status() == SagaStatus.COMPENSATING && s.stepIndex() == 0;
    assertThrows(
        IllegalStateException.class,
        () ->
            orchestrator(store)
                .start(
                    new SagaDefinition<>("payment", List.of(a, b)),
                    new Codec(),
                    "s-10",
                    new ArrayList<>()));
    assertEquals(List.of("do:reserve"), log);
    assertEquals(SagaStatus.IN_DOUBT, store.find("s-10").orElseThrow().status());
  }

  /** Paso remoto cuyo efecto ocurre pero cuya respuesta se pierde. */
  static final class LostResponseStep extends Step {
    final AtomicInteger externalEffects = new AtomicInteger();

    LostResponseStep(String name, List<String> log) {
      super(name, log);
    }

    @Override
    public List<String> execute(List<String> context) throws Exception {
      attempts.incrementAndGet();
      externalEffects.incrementAndGet(); // el débito ocurrió en el participante...
      throw new IOException("connection reset"); // ...pero la respuesta nunca llegó
    }
  }

  private static SagaOrchestrator noRetry(SagaStateStore store) {
    return new SagaOrchestrator(
        store, RetryPolicy.none(), RetryPolicy.none(), SagaListener.none(), Clock.systemUTC());
  }

  /**
   * Regresión: un efecto confirmado de forma remota cuya respuesta se perdió no debe producir una
   * saga COMPENSATED mientras el efecto siga existiendo. Sin una respuesta de verificación la saga
   * queda IN_DOUBT y no se deshace nada.
   */
  @Test
  void anUncertainFailureIsNeverTreatedAsNoEffect() {
    List<String> log = new ArrayList<>();
    Step reserve = new Step("reserve", log);
    LostResponseStep debit = new LostResponseStep("debit", log);
    SagaStateStore store = new InMemorySagaStateStore();
    SagaResult<List<String>> r =
        noRetry(store)
            .start(
                new SagaDefinition<>("payment", List.of(reserve, debit)),
                new Codec(),
                "s-11",
                new ArrayList<>());
    assertEquals(SagaStatus.IN_DOUBT, r.status());
    assertEquals("debit", r.failedStep());
    assertEquals(1, debit.externalEffects.get());
    assertEquals(List.of("do:reserve"), log, "nothing compensated while in doubt");
    SagaState stored = store.find("s-11").orElseThrow();
    assertEquals(SagaStatus.IN_DOUBT, stored.status());
    assertEquals(1, stored.stepIndex());
    assertTrue(!stored.status().terminal(), "recovery will ask again");
  }

  @Test
  void inDoubtSagasAreStaleForRecoveryInJdbc() throws Exception {
    SagaStateStore store = jdbcStore();
    List<String> log = new ArrayList<>();
    noRetry(store)
        .start(
            new SagaDefinition<>("payment", List.of(new LostResponseStep("debit", log))),
            new Codec(),
            "s-15",
            new ArrayList<>());
    assertEquals(SagaStatus.IN_DOUBT, store.find("s-15").orElseThrow().status());
    assertEquals(1, store.findStale(Instant.now().plusSeconds(1), 10).size());
  }

  @Test
  void aVerifiedEffectLetsTheSagaContinue() {
    List<String> log = new ArrayList<>();
    LostResponseStep debit = new LostResponseStep("debit", log);
    debit.verification = StepCheck.applied(List.of("debit-confirmed"));
    Step send = new Step("send", log);
    SagaResult<List<String>> r =
        noRetry(new InMemorySagaStateStore())
            .start(
                new SagaDefinition<>("payment", List.of(debit, send)),
                new Codec(),
                "s-12",
                new ArrayList<>());
    assertEquals(SagaStatus.COMPLETED, r.status());
    assertEquals(List.of("debit-confirmed", "send"), r.context());
    assertEquals(1, debit.externalEffects.get(), "the debit is not repeated");
  }

  @Test
  void aVerifiedTerminalRejectionCompensatesOnlyThePreviousSteps() {
    List<String> log = new ArrayList<>();
    Step reserve = new Step("reserve", log);
    Step debit = new Step("debit", log);
    debit.failExecute = 1; // fallo transitorio, sin reintentos configurados
    debit.verification = StepCheck.rejected();
    SagaResult<List<String>> r =
        noRetry(new InMemorySagaStateStore())
            .start(
                new SagaDefinition<>("payment", List.of(reserve, debit)),
                new Codec(),
                "s-13",
                new ArrayList<>());
    assertEquals(SagaStatus.COMPENSATED, r.status());
    assertEquals(List.of("do:reserve", "undo:reserve"), log);
  }

  @Test
  void recoveryResolvesAnInDoubtSagaOnceTheParticipantAnswers() {
    List<String> log = new ArrayList<>();
    Step reserve = new Step("reserve", log);
    LostResponseStep debit = new LostResponseStep("debit", log);
    Step send = new Step("send", log);
    SagaStateStore store = new InMemorySagaStateStore();
    SagaDefinition<List<String>> def =
        new SagaDefinition<>("payment", List.of(reserve, debit, send));
    assertEquals(
        SagaStatus.IN_DOUBT,
        noRetry(store).start(def, new Codec(), "s-14", new ArrayList<>()).status());
    // sigue desconocido en la siguiente verificación: permanece en duda, nada se deshace
    assertEquals(SagaStatus.IN_DOUBT, noRetry(store).resume(def, new Codec(), "s-14").status());
    debit.verification = StepCheck.applied(List.of("reserve", "debit"));
    SagaResult<List<String>> r = noRetry(store).resume(def, new Codec(), "s-14");
    assertEquals(SagaStatus.COMPLETED, r.status());
    assertEquals(List.of("do:reserve", "do:send"), log);
    assertEquals(3, debit.verifications.get());
    assertEquals(1, debit.externalEffects.get());
  }

  @Test
  void onlyClientErrorsAreDefiniteRejections() {
    assertTrue(StepFailures.isDefiniteRejection(new SagaStepRejectedException("no", null)));
    assertTrue(
        StepFailures.isDefiniteRejection(
            new BusinessException(
                ErrorDefinition.of("AXZ-TEST-0001", ErrorCategory.UNPROCESSABLE, "funds"),
                "insufficient funds",
                Map.of())));
    assertTrue(
        !StepFailures.isDefiniteRejection(
            new BusinessException(
                ErrorDefinition.of("AXZ-TEST-0002", ErrorCategory.CONFLICT, "in progress"),
                "in progress",
                Map.of())));
    assertTrue(!StepFailures.isDefiniteRejection(new SocketTimeoutException("read timed out")));
    assertTrue(!StepFailures.isDefiniteRejection(new IllegalStateException("bug")));
    assertTrue(
        StepFailures.isDefiniteRejection(
            new IllegalStateException("wrapped", new SagaStepRejectedException("no", null))));
  }
}
