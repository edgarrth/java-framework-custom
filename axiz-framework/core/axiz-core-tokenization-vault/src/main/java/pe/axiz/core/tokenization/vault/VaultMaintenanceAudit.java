package pe.axiz.core.tokenization.vault;

/**
 * Debe confirmar evidencia durable y sellada antes de retornar; un log o una cola en memoria no son
 * suficientes.
 */
@FunctionalInterface
public interface VaultMaintenanceAudit {
  void publishConfirmed(VaultMaintenanceEvent event);
}
