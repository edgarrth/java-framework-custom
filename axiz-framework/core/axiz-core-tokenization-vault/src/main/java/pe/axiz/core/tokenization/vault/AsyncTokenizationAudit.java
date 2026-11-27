package pe.axiz.core.tokenization.vault;

import java.util.concurrent.CompletionStage;

/** Termina solo tras la confirmación durable; los fallos deben propagarse. */
@FunctionalInterface
public interface AsyncTokenizationAudit {
  CompletionStage<Void> publishConfirmed(TokenAuditEntry entry);
}
