package pe.axiz.spring.outbox;

import java.util.Objects;
import pe.axiz.core.commons.tx.TransactionRunner;

/** Transaction boundary dedicated to the relay, separate from business transaction selection. */
public record OutboxRelayTransactions(TransactionRunner runner) {
  public OutboxRelayTransactions {
    Objects.requireNonNull(runner);
  }
}
