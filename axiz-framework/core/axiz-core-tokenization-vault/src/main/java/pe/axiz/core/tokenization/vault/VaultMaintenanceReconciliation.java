package pe.axiz.core.tokenization.vault;

/**
 * Acuse durable y restringido; nunca un replay en texto plano ni una instrucción de reintento a
 * ciegas.
 */
@FunctionalInterface
public interface VaultMaintenanceReconciliation {
  void recordConfirmed(UncertainVaultMaintenance operation);
}
