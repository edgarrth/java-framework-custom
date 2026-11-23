package pe.axiz.core.tokenization.vault;

import java.util.UUID;

/**
 * Alerta técnica de reconciliación cuando un permiso de concurrencia compartido puede seguir
 * reservado.
 */
@FunctionalInterface
public interface TokenBudgetLeaseAlarm {
  /**
   * No puede adjuntarse ningún valor de origen, subject, tenant, token ni excepción del proveedor.
   */
  void uncertain(String namespace, UUID leaseId);
}
