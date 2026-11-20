package pe.axiz.core.tokenization.vault;

import java.util.UUID;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.tokenization.TokenOperation;

/** Native asynchronous distributed admission; no blocking adapter is permitted on event loops. */
public interface AsyncTokenBudget {
  CompletionStage<Permit> acquire(TokenCaller caller, TokenOperation operation, VaultScope scope);

  /** Associates admission with the audit operation; providers can retain this recovery identity. */
  default CompletionStage<Permit> acquire(
      TokenCaller caller, TokenOperation operation, VaultScope scope, UUID operationId) {
    return acquire(caller, operation, scope);
  }

  CompletionStage<Void> beforeWrite(TokenCaller caller, VaultScope scope);

  boolean distributed();

  /** Owned concurrency lease; release acknowledges the shared store. */
  interface Permit {
    CompletionStage<Void> release();
  }
}
