package pe.axiz.kit.r2dbc;

import io.r2dbc.spi.R2dbcNonTransientResourceException;
import pe.axiz.core.commons.tx.UnknownTransactionOutcome;

/** A commit was attempted but not confirmed; reconcile before retrying the work. */
public final class TransactionOutcomeUnknownException extends R2dbcNonTransientResourceException
    implements UnknownTransactionOutcome {
  private static final long serialVersionUID = 1L;

  public TransactionOutcomeUnknownException(Throwable cause) {
    super("Transaction commit outcome is unknown; reconcile before retrying", "08007", 0, cause);
  }
}
