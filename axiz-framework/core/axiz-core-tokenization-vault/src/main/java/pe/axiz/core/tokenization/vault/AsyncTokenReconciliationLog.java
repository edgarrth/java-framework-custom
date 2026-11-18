package pe.axiz.core.tokenization.vault;

import java.util.concurrent.CompletionStage;

/** Completes only after durable acknowledgement; failures must propagate. */
@FunctionalInterface
public interface AsyncTokenReconciliationLog {
  CompletionStage<Void> recordConfirmed(UncertainTokenOperation operation);
}
