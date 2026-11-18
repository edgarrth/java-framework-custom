package pe.axiz.core.tokenization.vault;

import org.jspecify.annotations.Nullable;

/**
 * Server-owned administrative policy, including tenant/type/domain restrictions on every record.
 */
@FunctionalInterface
public interface VaultMaintenanceAccess {
  void check(
      TokenCaller caller,
      VaultMaintenanceEvent.Action action,
      String keysetId,
      @Nullable VaultScope scope);
}
