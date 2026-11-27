package pe.axiz.core.tokenization.vault;

import java.util.UUID;
import pe.axiz.core.tokenization.TokenOperation;

/**
 * Admisión distribuida fail-closed; las implementaciones aplican ventanas por subject/cliente y por
 * tenant.
 */
public interface TokenBudget {
  Permit acquire(TokenCaller caller, TokenOperation operation, VaultScope scope);

  /**
   * Asocia la admisión con la operación de auditoría; los proveedores pueden conservar esta
   * identidad de recuperación.
   */
  default Permit acquire(
      TokenCaller caller, TokenOperation operation, VaultScope scope, UUID operationId) {
    return acquire(caller, operation, scope);
  }

  void beforeWrite(TokenCaller caller, VaultScope scope);

  /**
   * Indica si los límites y los leases son atómicos entre todas las réplicas de este despliegue.
   */
  boolean distributed();

  /** Lease de concurrencia propio, liberado cuando la operación termina realmente. */
  interface Permit extends AutoCloseable {
    @Override
    void close();
  }
}
