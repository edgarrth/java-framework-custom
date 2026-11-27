package pe.axiz.core.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.PayloadProtector;
import pe.axiz.core.lock.LockLostException;
import pe.axiz.core.resilience.RetryPolicy;

class SagaOwnershipTest {
  private static final ContextCodec<String> CODEC =
      new ContextCodec<>() {
        @Override
        public String encode(String value) {
          return value;
        }

        @Override
        public String decode(String value) {
          return value;
        }
      };

  @Test
  void concurrentResumeCannotRunOrCompensateTheActiveOwnersStep() throws Exception {
    race(false);
  }

  @Test
  void expiredLeaseAndObservedAbsenceCannotCompensateAnEffectStillInFlight() throws Exception {
    race(true);
  }

  private static void race(boolean expire) throws Exception {
    var clock = new TestClock();
    var store = new InMemorySagaStateStore(clock);
    var entered = new CountDownLatch(1);
    var deliver = new CountDownLatch(1);
    var applied = new AtomicBoolean();
    var compensated = new AtomicBoolean();
    var failure = new AtomicReference<Throwable>();
    var fence = new AtomicLong();
    SagaStep<String> participant =
        new SagaStep<>() {
          @Override
          public String name() {
            return "effect";
          }

          @Override
          public String execute(String value) {
            throw new AssertionError("fenced entry required");
          }

          @Override
          public String execute(String value, SagaStepExecution execution) {
            fence.set(execution.fence());
            entered.countDown();
            // Se modela una petición remota ya enviada que ignora la interrupción local.
            boolean waiting = true;
            while (waiting) {
              try {
                waiting = !deliver.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException ignored) {
                /* El efecto remoto llega igualmente. */
              }
              if (waiting && deliver.getCount() != 0)
                throw new AssertionError("test did not release effect");
            }
            applied.set(true);
            return value;
          }

          @Override
          public void compensate(String value) {
            compensated.set(true);
            applied.set(false);
          }

          @Override
          public StepCheck<String> verify(String value) {
            return applied.get() ? StepCheck.applied(value) : StepCheck.notApplied();
          }
        };
    var definition = new SagaDefinition<>("synthetic", List.of(participant));
    Thread original =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    engine(store, clock).start(definition, CODEC, "same-saga", "context");
                  } catch (Throwable problem) {
                    failure.set(problem);
                  }
                });
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertEquals(SagaStatus.IN_DOUBT, store.find("same-saga").orElseThrow().status());
      assertTrue(fence.get() > 0);
      if (expire) {
        clock.advance(Duration.ofMinutes(2));
        assertEquals(
            SagaStatus.IN_DOUBT,
            engine(store, clock).resume(definition, CODEC, "same-saga").status());
      } else {
        assertThrows(
            SagaConcurrencyException.class,
            () -> engine(store, clock).resume(definition, CODEC, "same-saga"));
      }
      assertFalse(compensated.get());
    } finally {
      deliver.countDown();
      original.join(5000);
    }
    assertFalse(original.isAlive());
    assertTrue(applied.get());
    assertFalse(compensated.get());
    if (expire) {
      assertTrue(
          failure.get() instanceof SagaConcurrencyException
              || failure.get() instanceof LockLostException);
      assertEquals(SagaStatus.IN_DOUBT, store.find("same-saga").orElseThrow().status());
      assertEquals(
          SagaStatus.COMPLETED,
          engine(store, clock).resume(definition, CODEC, "same-saga").status());
    } else assertNull(failure.get());
    assertEquals(SagaStatus.COMPLETED, store.find("same-saga").orElseThrow().status());
  }

  @Test
  void expiredOwnerCannotRenewSaveOrReleaseItsSuccessor() {
    var clock = new TestClock();
    var store = new InMemorySagaStateStore(clock);
    SagaState initial =
        SagaState.builder()
            .sagaId("s")
            .sagaName("test")
            .status(SagaStatus.RUNNING)
            .context("c")
            .updatedAt(clock.instant())
            .build();
    store.create(initial);
    var old = store.tryAcquire("s", Duration.ofSeconds(1)).orElseThrow();
    clock.advance(Duration.ofSeconds(2));
    var next = store.tryAcquire("s", Duration.ofSeconds(2)).orElseThrow();
    assertFalse(old.renew(Duration.ofSeconds(1)));
    assertThrows(
        SagaConcurrencyException.class,
        () ->
            store.update(
                initial.next(SagaStatus.IN_DOUBT, 0, "c", null, clock.instant()), old.token()));
    old.release();
    assertTrue(store.tryAcquire("s", Duration.ofSeconds(1)).isEmpty());
    store.update(initial.next(SagaStatus.IN_DOUBT, 0, "c", null, clock.instant()), next.token());
    next.release();
    assertTrue(store.tryAcquire("s", Duration.ofSeconds(1)).isPresent());
  }

  @Test
  void leaseLifecycleExcludesActiveOwnersFromRecoveryAndRejectsInvalidWindows() {
    var clock = new TestClock();
    var store = new InMemorySagaStateStore(clock);
    assertTrue(store.tryAcquire("missing", Duration.ofSeconds(1)).isEmpty());
    for (Duration invalid : List.of(Duration.ZERO, Duration.ofSeconds(-1)))
      assertThrows(IllegalArgumentException.class, () -> store.tryAcquire("missing", invalid));
    var state =
        SagaState.builder()
            .sagaId("s")
            .sagaName("test")
            .status(SagaStatus.RUNNING)
            .context("c")
            .updatedAt(clock.instant().minusSeconds(1))
            .build();
    store.create(state);
    var owner = store.tryAcquire("s", Duration.ofSeconds(2)).orElseThrow();
    assertEquals("s", owner.resource());
    assertTrue(store.findStale(clock.instant(), 10).isEmpty());
    assertTrue(owner.renew(Duration.ofSeconds(3)));
    for (Duration invalid : List.of(Duration.ZERO, Duration.ofSeconds(-1)))
      assertThrows(IllegalArgumentException.class, () -> owner.renew(invalid));
    clock.advance(Duration.ofSeconds(4));
    assertEquals(List.of(state), store.findStale(clock.instant(), 10));
    owner.release();
    assertFalse(owner.renew(Duration.ofSeconds(1)));
    assertThrows(
        SagaConcurrencyException.class,
        () ->
            store.update(
                state.next(SagaStatus.RUNNING, 0, "c", null, clock.instant()), owner.token()));
    for (Duration invalid : List.of(Duration.ofMillis(99), Duration.ofMinutes(11)))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new SagaOrchestrator(
                  store,
                  RetryPolicy.none(),
                  RetryPolicy.none(),
                  SagaListener.none(),
                  clock,
                  PayloadProtector.NONE,
                  invalid));
  }

  @Test
  void successorFenceIsHigherAndParticipantRejectsAStaleExecutionIdentity() {
    var clock = new TestClock();
    var store = new InMemorySagaStateStore(clock);
    var first = new AtomicReference<SagaStepExecution>();
    var latest = new AtomicLong();
    var verified = new AtomicInteger();
    SagaStep<String> participant =
        new SagaStep<>() {
          @Override
          public String name() {
            return "effect";
          }

          @Override
          public String execute(String value) {
            throw new AssertionError("identity required");
          }

          @Override
          public String execute(String value, SagaStepExecution identity) {
            if (identity.fence() < latest.get())
              throw new IllegalStateException("stale fence rejected");
            latest.set(identity.fence());
            first.compareAndSet(null, identity);
            throw new IllegalStateException("synthetic unknown acknowledgement");
          }

          @Override
          public StepCheck<String> verify(String value, SagaStepExecution identity) {
            latest.accumulateAndGet(identity.fence(), Math::max);
            return verified.incrementAndGet() == 1 ? StepCheck.unknown() : StepCheck.applied(value);
          }

          @Override
          public void compensate(String value) {
            throw new AssertionError("must not compensate");
          }
        };
    var definition = new SagaDefinition<>("fenced", List.of(participant));
    assertEquals(
        SagaStatus.IN_DOUBT,
        engine(store, clock).start(definition, CODEC, "fenced-id", "c").status());
    assertTrue(store.find("fenced-id").orElseThrow().error().contains("IllegalStateException"));
    assertEquals(
        SagaStatus.COMPLETED, engine(store, clock).resume(definition, CODEC, "fenced-id").status());
    assertTrue(latest.get() > first.get().fence());
    assertThrows(IllegalStateException.class, () -> participant.execute("c", first.get()));
    assertThrows(IllegalArgumentException.class, () -> new SagaStepExecution("", "step", 1));
    assertThrows(IllegalArgumentException.class, () -> new SagaStepExecution("s", "", 1));
    assertThrows(IllegalArgumentException.class, () -> new SagaStepExecution("s", "step", 0));
  }

  private static SagaOrchestrator engine(SagaStateStore store, Clock clock) {
    return new SagaOrchestrator(
        store, RetryPolicy.none(), RetryPolicy.none(), SagaListener.none(), clock);
  }

  private static final class TestClock extends Clock {
    private final AtomicReference<Instant> now =
        new AtomicReference<>(Instant.parse("2026-11-18T00:00:00Z"));

    void advance(Duration duration) {
      now.updateAndGet(at -> at.plus(duration));
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }
}
