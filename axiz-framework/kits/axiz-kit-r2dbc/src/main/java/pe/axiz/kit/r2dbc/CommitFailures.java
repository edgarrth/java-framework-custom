package pe.axiz.kit.r2dbc;

import io.r2dbc.spi.R2dbcDataIntegrityViolationException;
import io.r2dbc.spi.R2dbcException;
import io.r2dbc.spi.R2dbcRollbackException;

/** Preserve explicit server rejection; a transport error never proves a rollback. */
final class CommitFailures {
  private CommitFailures() {}

  static RuntimeException classify(Throwable failure) {
    if (failure instanceof R2dbcException rejected) {
      String state = rejected.getSqlState();
      // SQLState 40003 means statement completion UNKNOWN, despite its class 40 prefix.
      if (!"40003".equals(state)
          && (rejected instanceof R2dbcRollbackException
              || rejected instanceof R2dbcDataIntegrityViolationException
              || "40001".equals(state)
              || "40P01".equals(state)
              || (state != null && state.startsWith("23")))) return rejected;
    }
    return new TransactionOutcomeUnknownException(failure);
  }
}
