package pe.axiz.core.tokenization.vault;

/**
 * Alerta técnica cuando ni siquiera la reconciliación durable del mantenimiento está disponible.
 */
@FunctionalInterface
public interface VaultMaintenanceAlarm {
  void failed(UncertainVaultMaintenance operation);
}
