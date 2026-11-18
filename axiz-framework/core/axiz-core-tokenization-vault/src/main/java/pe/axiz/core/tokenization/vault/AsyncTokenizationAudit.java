package pe.axiz.core.tokenization.vault;

import java.util.concurrent.CompletionStage;

/** Completes only after durable acknowledgement; failures must propagate. */
@FunctionalInterface
public interface AsyncTokenizationAudit {
  CompletionStage<Void> publishConfirmed(TokenAuditEntry entry);
}
