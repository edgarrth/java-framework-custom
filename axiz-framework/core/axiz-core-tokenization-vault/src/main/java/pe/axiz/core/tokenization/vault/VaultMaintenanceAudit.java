package pe.axiz.core.tokenization.vault;

/**
 * Must confirm durable, sealed evidence before returning; a log or in-memory queue is insufficient.
 */
@FunctionalInterface
public interface VaultMaintenanceAudit {
  void publishConfirmed(VaultMaintenanceEvent event);
}
