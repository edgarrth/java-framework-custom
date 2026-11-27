package pe.axiz.kit.reactor.audit;

import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.audit.AuditIntegrity;
import pe.axiz.core.audit.ChainCheckpoint;
import reactor.core.publisher.Mono;

/**
 * Estado de cadena nativo. Advance se une a la transacción de quien llama; checkpoint lee el estado
 * confirmado.
 */
public interface ReactiveAuditChainState {
  /**
   * Identidad del recurso de transacción, usada para rechazar un outbox o gestor de transacciones
   * no relacionado.
   */
  @Nullable Object transactionResource();

  /** Bloquea y avanza la cadena en la transacción reactiva activa; nunca abre una independiente. */
  Mono<AuditIntegrity> advance(String chainId, UnaryOperator<AuditIntegrity> step);

  /**
   * Lee el estado confirmado en una transacción independiente, nunca una cabeza de negocio en
   * curso.
   */
  Mono<ChainCheckpoint> checkpoint(String chainId);
}
