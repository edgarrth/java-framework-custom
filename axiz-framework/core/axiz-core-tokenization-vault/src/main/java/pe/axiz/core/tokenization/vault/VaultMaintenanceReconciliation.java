package pe.axiz.core.tokenization.vault;

/** Restricted durable acknowledgement; never a plaintext replay or a blind retry instruction. */
@FunctionalInterface
public interface VaultMaintenanceReconciliation {
  void recordConfirmed(UncertainVaultMaintenance operation);
}
