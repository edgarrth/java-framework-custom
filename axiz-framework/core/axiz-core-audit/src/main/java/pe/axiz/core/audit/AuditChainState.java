package pe.axiz.core.audit;

import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/** Chain state; durable implementations participate in the same transaction as the outbox. */
public interface AuditChainState {
  /** Opaque resource identity for durable composition; null for state without a transaction. */
  default @Nullable Object transactionResource() {
    return null;
  }

  /** Runs the step with the head locked, storing only a successfully calculated successor. */
  AuditIntegrity advance(String chainId, UnaryOperator<AuditIntegrity> step);

  /** Committed state, obtained independently of any uncommitted business transaction. */
  ChainCheckpoint checkpoint(String chainId);
}
