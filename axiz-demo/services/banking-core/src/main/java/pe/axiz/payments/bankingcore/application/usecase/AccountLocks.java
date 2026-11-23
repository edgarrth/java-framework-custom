package pe.axiz.payments.bankingcore.application.usecase;

import java.time.Duration;
import java.util.function.Supplier;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.lock.LockTemplate;

/**
 * Serializa los movimientos sobre una misma cuenta entre réplicas (lock distribuido en Redis): el
 * core responde de forma impredecible ante retenciones concurrentes sobre una cuenta.
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
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      throw new AxizException(CoreErrors.UNAVAILABLE, "interrupted waiting for " + key);
    } catch (Exception _) {
      throw new AxizException(CoreErrors.UNEXPECTED, "lock failure on " + key);
    }
  }
}
