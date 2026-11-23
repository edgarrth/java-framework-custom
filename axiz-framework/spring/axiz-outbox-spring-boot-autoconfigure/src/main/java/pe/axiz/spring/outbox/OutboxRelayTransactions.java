package pe.axiz.spring.outbox;

import java.util.Objects;
import pe.axiz.core.commons.tx.TransactionRunner;

/**
 * Límite de transacción dedicado al relay, separado de la selección de transacciones de negocio.
 */
public record OutboxRelayTransactions(TransactionRunner runner) {
  public OutboxRelayTransactions {
    Objects.requireNonNull(runner);
  }
}
