package pe.axiz.core.tokenization.vault;

/** Technical alert when even durable maintenance reconciliation is unavailable. */
@FunctionalInterface
public interface VaultMaintenanceAlarm {
  void failed(UncertainVaultMaintenance operation);
}
