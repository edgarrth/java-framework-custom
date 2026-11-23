package pe.axiz.core.tokenization.vault;

import java.util.concurrent.CompletionStage;

/** Termina solo tras la confirmación durable; los fallos deben propagarse. */
@FunctionalInterface
public interface AsyncTokenReconciliationLog {
  CompletionStage<Void> recordConfirmed(UncertainTokenOperation operation);
}
