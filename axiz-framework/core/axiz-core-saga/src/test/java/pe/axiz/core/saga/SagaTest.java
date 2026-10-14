package pe.axiz.core.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pe.axiz.core.commons.jdbc.JdbcTransactions;
import pe.axiz.core.resilience.Backoff;
import pe.axiz.core.resilience.RetryPolicy;

class SagaTest {

  /** Context: list of completed actions, serialized as comma separated text. */
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
    // simulate a crash after step 1 was persisted
    store.create(
        new SagaState("s-4", "payment", SagaStatus.RUNNING, 0, "", null, Instant.EPOCH, 0));
    store.update(
        new SagaState("s-4", "payment", SagaStatus.RUNNING, 1, "reserve", null, Instant.EPOCH, 1));
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
                new SagaState(
                    "s-4", "payment", SagaStatus.RUNNING, 1, "", null, Instant.EPOCH, 1)));
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
        new SagaState("s-5", "payment", SagaStatus.RUNNING, 1, "reserve", null, Instant.EPOCH, 0));
    store.create(
        new SagaState("s-6", "unknown", SagaStatus.RUNNING, 0, "", null, Instant.EPOCH, 0));

    SagaRecovery recovery =
        new SagaRecovery(store, orchestrator(store), Clock.systemUTC()).register(def, new Codec());

    assertEquals(1, recovery.resumeStale(Duration.ofMinutes(1), 10));
    assertEquals(List.of("do:send"), log);
    assertEquals(SagaStatus.COMPLETED, store.find("s-5").orElseThrow().status());
    assertEquals(SagaStatus.RUNNING, store.find("s-6").orElseThrow().status());
    assertEquals(0, recovery.resumeStale(Duration.ofMinutes(1), 10), "terminal sagas stay");
  }

  /** Store whose n-th update fails once (database unavailable at that moment). */
  static final class FlakyStore implements SagaStateStore {
    final InMemorySagaStateStore delegate = new InMemorySagaStateStore();
    int failOnUpdate;
    int updates;

    @Override
    public void create(SagaState state) {
      delegate.create(state);
    }

    @Override
    public void update(SagaState state) {
      if (++updates == failOnUpdate) {
        throw new IllegalStateException("database unavailable");
      }
      delegate.update(state);
    }

    @Override
    public java.util.Optional<SagaState> find(String sagaId) {
      return delegate.find(sagaId);
    }

    @Override
    public List<SagaState> findStale(Instant before, int limit) {
      return delegate.findStale(before, limit);
    }
  }

  /** Report case: a listener that throws after a step must not compensate that step. */
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
   * Report case: step "debit" ran but recording its result failed. The saga aborts and "debit" is
   * compensated too, not only the steps before it.
   */
  @Test
  void aStepThatRanButCouldNotBeRecordedIsCompensated() {
    List<String> log = new ArrayList<>();
    Step a = new Step("reserve", log);
    Step b = new Step("debit", log);
    Step c = new Step("send", log);
    FlakyStore store = new FlakyStore();
    store.failOnUpdate = 2; // 1: after reserve, 2: after debit
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

  /** A failure to record compensation progress is not reported as a failed compensation. */
  @Test
  void aStoreFailureDuringCompensationPropagatesAndResumeFinishesIt() {
    List<String> log = new ArrayList<>();
    Step a = new Step("reserve", log);
    Step b = new Step("send", log);
    b.failExecute = 101;
    FlakyStore store = new FlakyStore();
    // 1: after reserve, 2: COMPENSATING intent, 3: progress after undoing reserve
    store.failOnUpdate = 3;
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

  /** When even the intent to compensate cannot be recorded, nothing is undone. */
  @Test
  void nothingIsUndoneUnlessTheIntentToCompensateWasRecorded() {
    List<String> log = new ArrayList<>();
    Step a = new Step("reserve", log);
    Step b = new Step("send", log);
    b.failExecute = 101;
    FlakyStore store = new FlakyStore();
    store.failOnUpdate = 2; // the COMPENSATING intent
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
    assertEquals(SagaStatus.RUNNING, store.find("s-10").orElseThrow().status());
  }

  /** A remote step whose effect happens but whose response is lost (the report's scenario). */
  static final class LostResponseStep extends Step {
    final AtomicInteger externalEffects = new AtomicInteger();

    LostResponseStep(String name, List<String> log) {
      super(name, log);
    }

    @Override
    public List<String> execute(List<String> context) throws Exception {
      attempts.incrementAndGet();
      externalEffects.incrementAndGet(); // the debit happened at the participant...
      throw new java.io.IOException("connection reset"); // ...but the answer never arrived
    }
  }

  private static SagaOrchestrator noRetry(SagaStateStore store) {
    return new SagaOrchestrator(
        store, RetryPolicy.none(), RetryPolicy.none(), SagaListener.none(), Clock.systemUTC());
  }

  /**
   * Report case (second review, F03): an effect confirmed remotely whose response was lost must not
   * produce a COMPENSATED saga while the effect still exists. Without a verification answer the
   * saga is IN_DOUBT and nothing is undone.
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
  void aVerifiedAbsenceCompensatesOnlyThePreviousSteps() {
    List<String> log = new ArrayList<>();
    Step reserve = new Step("reserve", log);
    Step debit = new Step("debit", log);
    debit.failExecute = 1; // transient failure, no retries configured
    debit.verification = StepCheck.notApplied();
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
    // still unknown on the next check: stays in doubt, nothing undone
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
            new pe.axiz.core.errors.BusinessException(
                pe.axiz.core.errors.ErrorDefinition.of(
                    "AXZ-TEST-0001", pe.axiz.core.errors.ErrorCategory.UNPROCESSABLE, "funds"),
                "insufficient funds",
                java.util.Map.of())));
    assertTrue(
        !StepFailures.isDefiniteRejection(
            new pe.axiz.core.errors.BusinessException(
                pe.axiz.core.errors.ErrorDefinition.of(
                    "AXZ-TEST-0002", pe.axiz.core.errors.ErrorCategory.CONFLICT, "in progress"),
                "in progress",
                java.util.Map.of())));
    assertTrue(!StepFailures.isDefiniteRejection(new SocketTimeoutException("read timed out")));
    assertTrue(!StepFailures.isDefiniteRejection(new IllegalStateException("bug")));
    assertTrue(
        StepFailures.isDefiniteRejection(
            new IllegalStateException("wrapped", new SagaStepRejectedException("no", null))));
  }
}
