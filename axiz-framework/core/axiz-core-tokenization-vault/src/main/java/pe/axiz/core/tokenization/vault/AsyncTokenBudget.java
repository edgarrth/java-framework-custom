package pe.axiz.core.tokenization.vault;

import java.util.concurrent.CompletionStage;
import pe.axiz.core.tokenization.TokenOperation;

/** Native asynchronous distributed admission; no blocking adapter is permitted on event loops. */
public interface AsyncTokenBudget {
  CompletionStage<Permit> acquire(TokenCaller caller, TokenOperation operation, VaultScope scope);

  CompletionStage<Void> beforeWrite(TokenCaller caller, VaultScope scope);

  boolean distributed();

  /** Owned concurrency lease; release acknowledges the shared store. */
  interface Permit {
    CompletionStage<Void> release();
  }
}
