package pe.axiz.core.tokenization.vault;

import java.util.UUID;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.tokenization.TokenOperation;

/**
 * Admisión distribuida asíncrona nativa; no se permite ningún adaptador bloqueante en los event
 * loops.
 */
public interface AsyncTokenBudget {
  CompletionStage<Permit> acquire(TokenCaller caller, TokenOperation operation, VaultScope scope);

  /**
   * Asocia la admisión con la operación de auditoría; los proveedores pueden conservar esta
   * identidad de recuperación.
   */
  default CompletionStage<Permit> acquire(
      TokenCaller caller, TokenOperation operation, VaultScope scope, UUID operationId) {
    return acquire(caller, operation, scope);
  }

  CompletionStage<Void> beforeWrite(TokenCaller caller, VaultScope scope);

  boolean distributed();

  /** Lease de concurrencia propio; la liberación confirma el almacén compartido. */
  interface Permit {
    CompletionStage<Void> release();
  }
}
