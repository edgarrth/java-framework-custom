package pe.axiz.core.tokenization.vault;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.tokenization.Token;

/**
 * Evidencia de reconciliación restringida. UNCERTAIN significa que se desconoce el acuse del
 * almacenamiento; SUCCESS/CONFLICT significa que la operación terminó pero su auditoría final no se
 * confirmó. Solo se incluye un token completo para una mutación de registro.
 */
public record UncertainVaultMaintenance(VaultMaintenanceEvent event, @Nullable Token token) {
  public UncertainVaultMaintenance {
    Objects.requireNonNull(event);
    if ((event.result() != VaultMaintenanceEvent.Result.UNCERTAIN
            && event.result() != VaultMaintenanceEvent.Result.SUCCESS
            && event.result() != VaultMaintenanceEvent.Result.CONFLICT)
        || ((event.scope() == null) != (token == null)))
      throw new IllegalArgumentException("Invalid uncertain maintenance operation");
  }

  @Override
  public String toString() {
    return "UncertainVaultMaintenance[" + event.action() + "]";
  }
}
