package pe.axiz.core.audit;

import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Estado de la cadena; las implementaciones durables participan en la misma transacción que el
 * outbox.
 */
public interface AuditChainState {
  /** Identidad opaca del recurso para composición durable; null para estado sin transacción. */
  default @Nullable Object transactionResource() {
    return null;
  }

  /** Ejecuta el paso con la cabeza bloqueada y almacena solo un sucesor calculado con éxito. */
  AuditIntegrity advance(String chainId, UnaryOperator<AuditIntegrity> step);

  /**
   * Estado confirmado, obtenido de forma independiente de cualquier transacción de negocio sin
   * commit.
   */
  ChainCheckpoint checkpoint(String chainId);
}
