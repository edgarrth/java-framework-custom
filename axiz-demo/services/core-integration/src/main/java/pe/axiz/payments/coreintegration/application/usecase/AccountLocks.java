package pe.axiz.payments.coreintegration.application.usecase;

import java.time.Duration;
import java.util.function.Supplier;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.lock.LockTemplate;

/**
 * Serializes movements on the same account across replicas (distributed lock on Redis): the core
 * answers unpredictably to concurrent holds on one account.
 */
final class AccountLocks {

  static final Duration LEASE = Duration.ofSeconds(15);
  static final Duration WAIT = Duration.ofSeconds(3);

  private AccountLocks() {}

  static <T> T with(LockTemplate locks, String key, Supplier<T> work) {
    try {
      return locks.withLock(key, LEASE, WAIT, work::get);
    } catch (RuntimeException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AxizException(CoreErrors.UNAVAILABLE, "interrupted waiting for " + key);
    } catch (Exception e) {
      throw new AxizException(CoreErrors.UNEXPECTED, "lock failure on " + key);
    }
  }
}
