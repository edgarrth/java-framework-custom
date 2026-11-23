package pe.axiz.core.tokenization.vault;

/** Termina solo tras la confirmación durable; los fallos deben propagarse. */
@FunctionalInterface
public interface TokenizationAudit {
  void publishConfirmed(TokenAuditEntry entry);
}
