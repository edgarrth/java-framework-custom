package pe.axiz.core.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LockReleaseTest {
  private final RuntimeException release = new IllegalStateException("store unavailable");
  private final LockHandle handle =
      new LockHandle() {
        public String resource() {
          return "account";
        }

        public String token() {
          return "owner";
        }

        public boolean renew(Duration lease) {
          return true;
        }

        public void release() {
          throw release;
        }
      };

  @Test
  void aReleaseFailureCannotTurnCommittedWorkIntoARetryableError() throws Exception {
    AtomicInteger effects = new AtomicInteger();
    AtomicInteger observed = new AtomicInteger();
    LockTemplate locks =
        new LockTemplate(
            (resource, lease) -> Optional.of(handle),
            Duration.ofMillis(1),
            (resource, failure) -> {
              assertSame(release, failure);
              observed.incrementAndGet();
              throw new IllegalStateException("meter failed");
            });
    assertEquals(
        1,
        locks.withLock(
            "account", Duration.ofSeconds(10), Duration.ZERO, () -> effects.incrementAndGet()));
    assertEquals(1, effects.get());
    assertEquals(1, observed.get());
  }

  @Test
  void businessFailureRemainsPrimaryAndReleaseIsSuppressed() {
    LockTemplate locks =
        new LockTemplate(
            (resource, lease) -> Optional.of(handle), Duration.ofMillis(1), LockObserver.NONE);
    RuntimeException business = new IllegalArgumentException("business failed");
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                locks.withLock(
                    "account",
                    Duration.ofSeconds(10),
                    Duration.ZERO,
                    () -> {
                      throw business;
                    }));
    assertSame(business, thrown);
    assertEquals(1, thrown.getSuppressed().length);
    assertSame(release, thrown.getSuppressed()[0]);
  }
}
