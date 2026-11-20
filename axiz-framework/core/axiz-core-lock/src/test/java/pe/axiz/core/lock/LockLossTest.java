package pe.axiz.core.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.BusinessException;

/** A lease lost while the work runs never ends in a success. */
class LockLossTest {

  // Real scheduler integration needs room for instrumentation and concurrent CI work.
  private static final Duration LEASE = Duration.ofSeconds(1);

  @Test
  void lateAcquisitionIsExplicitlyReleasedWithoutRunningBusinessWork() {
    ScriptedHandle handle = new ScriptedHandle(n -> true);
    DistributedLock slow =
        (resource, lease) -> {
          try {
            Thread.sleep(80);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
          }
          return Optional.of(handle);
        };
    var template = new LockTemplate(slow, Duration.ofMillis(1), LockObserver.NONE);
    var failure =
        assertThrows(
            BusinessException.class,
            () ->
                template.withLock(
                    "late",
                    Duration.ofMillis(20),
                    Duration.ZERO,
                    () -> {
                      throw new AssertionError("Business ran with an expired acquisition");
                    }));
    assertEquals(LockErrors.BUSY, failure.definition());
    assertTrue(handle.released.get(), "Do not wait for the remote TTL to release a late handle");
    assertEquals(0, handle.renewals.get());
  }

  @Test
  void anInterruptedRenewerPreservesItsInterruptWithoutRenewing() throws Exception {
    ScriptedHandle handle = new ScriptedHandle(n -> true);
    var held = new LockTemplate.Lease(handle, Long.MAX_VALUE, Thread.currentThread());
    AtomicBoolean restored = new AtomicBoolean();
    Thread renewer =
        Thread.ofVirtual()
            .start(
                () -> {
                  Thread.currentThread().interrupt();
                  LockTemplate.renewLoop(held, LEASE, new AtomicBoolean());
                  restored.set(Thread.currentThread().isInterrupted());
                });
    renewer.join(Duration.ofSeconds(2));
    assertFalse(renewer.isAlive());
    assertTrue(restored.get());
    assertEquals(0, handle.renewals.get());
  }

  /** A handle whose renewals answer what the test says; counts them. */
  static final class ScriptedHandle implements LockHandle {
    final AtomicInteger renewals = new AtomicInteger();
    final AtomicBoolean released = new AtomicBoolean();
    final IntFunction<Boolean> answer;

    ScriptedHandle(IntFunction<Boolean> answer) {
      this.answer = answer;
    }

    @Override
    public String resource() {
      return "account:A-1";
    }

    @Override
    public String token() {
      return "owner-1";
    }

    @Override
    public boolean renew(Duration lease) {
      int n = renewals.incrementAndGet();
      return answer.apply(n);
    }

    @Override
    public void release() {
      released.set(true);
    }
  }

  private static LockTemplate template(ScriptedHandle handle) {
    return new LockTemplate(
        (resource, lease) -> Optional.of(handle), Duration.ofMillis(1), LockObserver.NONE);
  }

  @Test
  void aRefusedRenewalInterruptsTheWorkAndIsNeverASuccess() {
    ScriptedHandle handle = new ScriptedHandle(n -> false);
    AtomicBoolean effect = new AtomicBoolean();
    LockLostException e =
        assertThrows(
            LockLostException.class,
            () ->
                template(handle)
                    .withLock(
                        "account:A-1",
                        LEASE,
                        Duration.ofSeconds(1),
                        () -> {
                          new CountDownLatch(1).await(5, TimeUnit.SECONDS);
                          effect.set(true);
                          return "SUCCESS";
                        }));
    assertFalse(effect.get(), "the work was stopped before its effect");
    assertEquals(LockErrors.LOST, e.definition());
    assertEquals("account:A-1", e.parameters().get("resource"));
    assertInstanceOf(InterruptedException.class, e.getSuppressed()[0]);
    assertTrue(handle.released.get());
    assertFalse(Thread.currentThread().isInterrupted(), "the interrupt does not leak");
  }

  @Test
  void workCanCheckItsLeaseBeforeEachEffect() throws Exception {
    ScriptedHandle handle = new ScriptedHandle(n -> false);
    AtomicInteger effects = new AtomicInteger();
    assertThrows(
        LockLostException.class,
        () ->
            template(handle)
                .withLock(
                    "account:A-1",
                    LEASE,
                    Duration.ofSeconds(1),
                    lease -> {
                      assertEquals("owner-1", lease.token());
                      assertEquals("account:A-1", lease.resource());
                      lease.ensureHeld();
                      effects.incrementAndGet();
                      while (lease.held()) {
                        Thread.onSpinWait();
                      }
                      Thread.interrupted();
                      lease.ensureHeld();
                      effects.incrementAndGet();
                      return "SUCCESS";
                    }));
    assertEquals(1, effects.get(), "the second effect never ran");
  }

  @Test
  void aFailureOfTheWorkKeepsItsOwnExceptionAndCarriesTheLoss() {
    ScriptedHandle handle = new ScriptedHandle(n -> false);
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () ->
                template(handle)
                    .withLock(
                        "account:A-1",
                        LEASE,
                        Duration.ofSeconds(1),
                        lease -> {
                          while (lease.held()) {
                            Thread.onSpinWait();
                          }
                          Thread.interrupted();
                          throw new IllegalStateException("ledger rejected");
                        }));
    assertInstanceOf(LockLostException.class, e.getSuppressed()[0]);
  }

  @Test
  void aStoreThatFailsOnceIsRetriedAndTheWorkSucceeds() throws Exception {
    CountDownLatch thirdRenewal = new CountDownLatch(1);
    ScriptedHandle handle =
        new ScriptedHandle(
            n -> {
              if (n == 3) thirdRenewal.countDown();
              if (n == 1) {
                throw new IllegalStateException("store unavailable");
              }
              return true;
            });
    String result =
        template(handle)
            .withLock(
                "account:A-1",
                LEASE,
                Duration.ofSeconds(1),
                () -> {
                  assertTrue(thirdRenewal.await(5, TimeUnit.SECONDS));
                  return "SUCCESS";
                });
    assertEquals("SUCCESS", result);
  }

  @Test
  void aStoreThatKeepsFailingLosesTheLeaseWhenItsTimeRunsOut() {
    ScriptedHandle handle =
        new ScriptedHandle(
            n -> {
              throw new IllegalStateException("store unavailable");
            });
    LockLostException e =
        assertThrows(
            LockLostException.class,
            () ->
                template(handle)
                    .withLock(
                        "account:A-1",
                        LEASE,
                        Duration.ofSeconds(1),
                        () -> {
                          assertFalse(
                              new CountDownLatch(1).await(5, TimeUnit.SECONDS),
                              "lease loss must interrupt work");
                          return "SUCCESS";
                        }));
    assertInstanceOf(IllegalStateException.class, e.getCause());
  }

  @Test
  void anUnbrokenLeaseReturnsTheResult() throws Exception {
    ScriptedHandle handle = new ScriptedHandle(n -> true);
    Object result =
        template(handle)
            .withLock(
                "account:A-1",
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                lease -> {
                  lease.ensureHeld();
                  return lease;
                });
    assertTrue(((LockLease) result).token().equals("owner-1"));
    assertSame(LockErrors.LOST, new LockLostException("r", null).definition());
  }

  @Test
  void anAcquisitionThatAnswersAfterItsLeaseIsReleasedAndNeverRunsTheWork() {
    InMemoryDistributedLock store = new InMemoryDistributedLock(Clock.systemUTC());
    AtomicInteger attempts = new AtomicInteger();
    // the store grants the lease at once, but its answer arrives after the lease ran out; by then
    // the in-memory store, like Redis, already lets another owner take it
    DistributedLock slow =
        (resource, lease) -> {
          attempts.incrementAndGet();
          Optional<LockHandle> handle = store.tryAcquire(resource, lease);
          try {
            Thread.sleep(lease.toMillis() + 150);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return handle;
        };
    AtomicBoolean effect = new AtomicBoolean();
    BusinessException busy =
        assertThrows(
            BusinessException.class,
            () ->
                new LockTemplate(slow, Duration.ofMillis(1), LockObserver.NONE)
                    .withLock(
                        "account:A-1",
                        Duration.ofMillis(100),
                        Duration.ZERO,
                        lease -> {
                          lease.ensureHeld();
                          effect.set(true);
                          return "SUCCESS";
                        }));
    assertSame(LockErrors.BUSY, busy.definition());
    assertFalse(effect.get(), "the work never runs on a lease that already ran out");
    assertEquals(1, attempts.get());
    assertTrue(
        store.tryAcquire("account:A-1", Duration.ofSeconds(1)).isPresent(),
        "the late acquisition was released");
  }

  @Test
  void aSlowButTimelyAcquisitionKeepsOnlyWhatIsLeftOfItsLease() throws Exception {
    ScriptedHandle handle = new ScriptedHandle(n -> true);
    DistributedLock slow =
        (resource, lease) -> {
          try {
            Thread.sleep(40);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return Optional.of(handle);
        };
    String result =
        new LockTemplate(slow, Duration.ofMillis(1), LockObserver.NONE)
            .withLock(
                "account:A-1",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                lease -> {
                  lease.ensureHeld();
                  return "SUCCESS";
                });
    assertEquals("SUCCESS", result);
    long sent = System.nanoTime();
    assertTrue(
        LockTemplate.validUntil(sent, Duration.ofMillis(200)) - sent
            < Duration.ofMillis(200).toNanos(),
        "the drift margin is taken off the lease");
  }
}
