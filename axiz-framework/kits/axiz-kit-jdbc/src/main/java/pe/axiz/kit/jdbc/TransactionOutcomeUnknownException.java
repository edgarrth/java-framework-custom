package pe.axiz.kit.jdbc;

import java.sql.SQLException;
import pe.axiz.core.commons.tx.UnknownTransactionOutcome;

/**
 * La base de datos no confirmó el commit. Quien llama debe reconciliar el resultado antes de
 * reintentar: la transacción puede haber hecho commit.
 */
public final class TransactionOutcomeUnknownException extends SQLException
    implements UnknownTransactionOutcome {
  private static final long serialVersionUID = 1L;

  public TransactionOutcomeUnknownException(Throwable cause) {
    super("Transaction commit outcome is unknown; reconcile before retrying", "08007", cause);
  }
}
