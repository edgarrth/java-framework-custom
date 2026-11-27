package pe.axiz.core.tokenization.vault;

import org.jspecify.annotations.Nullable;

/**
 * Política administrativa propiedad del servidor, incluidas las restricciones de tenant, tipo y
 * dominio en cada registro.
 */
@FunctionalInterface
public interface VaultMaintenanceAccess {
  void check(
      TokenCaller caller,
      VaultMaintenanceEvent.Action action,
      String keysetId,
      @Nullable VaultScope scope);
}
