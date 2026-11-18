package pe.axiz.core.tokenization.vault;

/** Completes only after durable acknowledgement; failures must propagate. */
@FunctionalInterface
public interface TokenizationAudit {
  void publishConfirmed(TokenAuditEntry entry);
}
