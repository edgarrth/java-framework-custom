package pe.axiz.payments.coreintegration.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.lock.InMemoryDistributedLock;
import pe.axiz.core.lock.LockTemplate;

/** What a movement sees when its account lock is not obtained or the work fails. */
class AccountLocksTest {

  @Test
  void theWorkRunsUnderTheLockAndItsFailuresPassThrough() {
    LockTemplate locks =
        new LockTemplate(new InMemoryDistributedLock(Clock.systemUTC()), Duration.ofMillis(5));

    assertThat(AccountLocks.with(locks, "account:1", () -> "done")).isEqualTo("done");
    IllegalStateException failure = new IllegalStateException("core down");
    assertThatThrownBy(
            () ->
                AccountLocks.with(
                    locks,
                    "account:1",
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure);
  }

  @Test
  void anInterruptedWaitIsAnUnavailableDependencyAndKeepsTheInterrupt() {
    LockTemplate neverFree =
        new LockTemplate((resource, lease) -> Optional.empty(), Duration.ofMillis(5));

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> AccountLocks.with(neverFree, "account:1", () -> "never"))
          .isInstanceOfSatisfying(
              AxizException.class,
              e -> assertThat(e.definition()).isEqualTo(CoreErrors.UNAVAILABLE));
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }
}
