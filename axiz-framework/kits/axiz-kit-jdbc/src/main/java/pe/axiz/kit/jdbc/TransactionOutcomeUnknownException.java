package pe.axiz.kit.jdbc;

import java.sql.SQLException;

/**
 * The database did not confirm commit. The caller must reconcile the outcome before retrying: the
 * transaction may already have committed.
 */
public final class TransactionOutcomeUnknownException extends SQLException {
  private static final long serialVersionUID = 1L;

  public TransactionOutcomeUnknownException(Throwable cause) {
    super("Transaction commit outcome is unknown; reconcile before retrying", "08007", cause);
  }
}
