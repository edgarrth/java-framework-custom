package pe.axiz.core.tokenization.vault;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.tokenization.Token;

/** Restricted reconciliation evidence, including a full token only for a record mutation. */
public record UncertainVaultMaintenance(VaultMaintenanceEvent event, @Nullable Token token) {
  public UncertainVaultMaintenance {
    Objects.requireNonNull(event);
    if (event.result() != VaultMaintenanceEvent.Result.UNCERTAIN
        || ((event.scope() == null) != (token == null)))
      throw new IllegalArgumentException("Invalid uncertain maintenance operation");
  }

  @Override
  public String toString() {
    return "UncertainVaultMaintenance[" + event.action() + "]";
  }
}
