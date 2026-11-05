package pe.axiz.spring.audit;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.ClassUtils;
import pe.axiz.core.commons.TransactionOutcome;

/** Captures completion evidence; a failed commit is never inferred to be a rollback. */
final class JdbcTransactionOutcome {
  private final AtomicReference<TransactionOutcome> value =
      new AtomicReference<>(TransactionOutcome.UNKNOWN);

  static JdbcTransactionOutcome capture() {
    var outcome = new JdbcTransactionOutcome();
    if (transactionsAvailable()) SpringTransactions.capture(outcome);
    return outcome;
  }

  void complete(int status) {
    value.set(
        status == TransactionSynchronization.STATUS_COMMITTED
            ? TransactionOutcome.COMMITTED
            : status == TransactionSynchronization.STATUS_ROLLED_BACK
                ? TransactionOutcome.ROLLED_BACK
                : TransactionOutcome.UNKNOWN);
  }

  TransactionOutcome local(@Nullable Throwable failure) {
    if (unknownCommit(failure)) return TransactionOutcome.UNKNOWN;
    if (value.get() == TransactionOutcome.UNKNOWN
        && transactionsAvailable()
        && SpringTransactions.unexpectedRollback(failure)) return TransactionOutcome.ROLLED_BACK;
    return value.get();
  }

  static boolean unknownCommit(@Nullable Throwable failure) {
    var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
      if (cause instanceof SQLRecoverableException
          || (transactionsAvailable() && SpringTransactions.systemFailure(cause))) return true;
      if (cause instanceof SQLException sql
          && sql.getSQLState() != null
          && sql.getSQLState().startsWith("08")) return true;
    }
    return false;
  }

  private static boolean transactionsAvailable() {
    return ClassUtils.isPresent(
        "org.springframework.transaction.support.TransactionSynchronizationManager", null);
  }

  /** Keep linkage to the optional Spring transaction module behind the classpath guard. */
  private static final class SpringTransactions {
    static void capture(JdbcTransactionOutcome outcome) {
      if (TransactionSynchronizationManager.isSynchronizationActive())
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
              @Override
              public void afterCompletion(int status) {
                outcome.complete(status);
              }
            });
    }

    static boolean unexpectedRollback(@Nullable Throwable failure) {
      return failure instanceof UnexpectedRollbackException;
    }

    static boolean systemFailure(Throwable failure) {
      return failure instanceof TransactionSystemException;
    }
  }
}
