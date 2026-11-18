package pe.axiz.kit.reactor.audit;

import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.audit.AuditIntegrity;
import pe.axiz.core.audit.ChainCheckpoint;
import reactor.core.publisher.Mono;

/** Native chain state. Advance joins the caller's transaction; checkpoint reads committed state. */
public interface ReactiveAuditChainState {
  /**
   * Identity of the transaction resource, used to reject an unrelated outbox/transaction manager.
   */
  @Nullable Object transactionResource();

  /**
   * Locks and advances the chain in the active reactive transaction; never opens an independent
   * one.
   */
  Mono<AuditIntegrity> advance(String chainId, UnaryOperator<AuditIntegrity> step);

  /** Reads committed state in an independent transaction, never an in-flight business head. */
  Mono<ChainCheckpoint> checkpoint(String chainId);
}
